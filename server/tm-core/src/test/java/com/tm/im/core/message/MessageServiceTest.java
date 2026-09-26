package com.tm.im.core.message;

import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.common.id.IdGenerator;
import com.tm.im.core.channel.MessagePushPort;
import com.tm.im.domain.entity.Conversation;
import com.tm.im.domain.entity.ConversationMember;
import com.tm.im.domain.entity.Friendship;
import com.tm.im.domain.entity.Message;
import com.tm.im.domain.enums.ConvType;
import com.tm.im.domain.enums.FriendshipStatus;
import com.tm.im.domain.enums.MemberRole;
import com.tm.im.domain.enums.MessageType;
import com.tm.im.domain.repository.ConversationMembership;
import com.tm.im.domain.repository.ConversationRepository;
import com.tm.im.domain.repository.FriendshipRepository;
import com.tm.im.domain.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * {@link MessageService} 的写路径规则。
 *
 * <p><b>为什么值得把这些组合逐个钉住</b>：错误码回归是最难发现的一类回归——
 * 接口结构没变、数据也对，只是本该 40003 的地方返回了 200。
 * 这里穷举的不是「消息能不能发出去」（那由集成测试证明），而是
 * <b>每一种非法输入对应哪个错误码、以及失败时有没有产生副作用</b>。
 *
 * <p>其中三条断言是踩过坑才加的：
 * <ol>
 *   <li>幂等重放<b>不再取号</b>（否则每次重试都在会话里留一个永久空洞）；</li>
 *   <li>被拒绝的请求<b>不取号、不落库</b>（否则一次误发就占掉一个 seq）；</li>
 *   <li>序号撞主键时用「库内最大 seq」自愈并重试（这是 Redis 被清空后唯一的修复机会）。</li>
 * </ol>
 *
 * <p>下半部分是续传读取（{@link MessageService#sync}）的规则。它与写入共用一条不变量：
 * {@code seq} 允许有空洞，因此读取侧只能表达「凡 seq 大于游标的都已返回」——
 * 这条不变量决定了三件容易被改错的事：{@code has_more} 必须由「多取一行」得出
 * （不能查 maxSeq 再比大小）、{@code has_more=true} 时必需要有一条消息可推进游标
 * （否则客户端死循环）、{@code truncated} 不能由「某行不存在」反推（那是假警报）。
 */
class MessageServiceTest {

    private static final long CONV = 1001L;
    private static final long ALICE = 2002L;
    private static final long BOB = 2003L;

    private FakeConversations conversations;
    private FakeMessages messages;
    private FakeFriendships friendships;
    private RecordingPush push;
    private MessageService service;

    @BeforeEach
    void setUp() {
        conversations = new FakeConversations();
        messages = new FakeMessages();
        friendships = new FakeFriendships();
        push = new RecordingPush();
        MessageProperties props = new MessageProperties();
        service = new MessageService(conversations, messages, friendships, push,
                new SequentialIds(), props, ZoneId.of("Asia/Shanghai"));

        conversations.putConversation(CONV, ConvType.DIRECT);
        conversations.addMember(CONV, ALICE, BOB);
        friendships.accept(ALICE, BOB);
    }

    private MessageService.SendCommand cmd(String text) {
        return new MessageService.SendCommand(CONV, ALICE, "c-1", MessageType.TEXT,
                "{\"text\":\"" + text + "\"}", 0, true);
    }

    @Test
    @DisplayName("文本消息：分配 seq、落库，并只推给对方（不回推给自己）")
    void sendTextPushesOnlyToPeer() {
        MessageService.SendOutcome outcome = service.send(cmd("你好"));

        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.message().getSeq()).isEqualTo(1L);
        assertThat(outcome.message().getConvId()).isEqualTo(CONV);
        assertThat(outcome.message().getSenderId()).isEqualTo(ALICE);
        assertThat(outcome.pushed()).as("只推给对方一条连接").isEqualTo(1);
        assertThat(push.recipients).as("发送者不该收到自己的消息").containsExactly(BOB);
    }

    @Test
    @DisplayName("幂等重放：同一 client_msg_id 再发不取号、不落库、不重复推送")
    void idempotentReplayDoesNotConsumeSeq() {
        MessageService.SendCommand first = cmd("你好");
        MessageService.SendOutcome one = service.send(first);
        int seqCallsAfterFirst = conversations.nextSeqCalls;
        int insertsAfterFirst = messages.insertCalls;

        MessageService.SendOutcome two = service.send(first);

        assertThat(two.replayed()).isTrue();
        assertThat(two.message().getSeq()).isEqualTo(one.message().getSeq());
        assertThat(conversations.nextSeqCalls)
                .as("重放不能再取号：取号即消耗，会留下永久空洞")
                .isEqualTo(seqCallsAfterFirst);
        assertThat(messages.insertCalls)
                .as("重放不能再落库")
                .isEqualTo(insertsAfterFirst);
        assertThat(two.pushed()).as("重放不能重复推送").isZero();
        assertThat(push.recipients).containsExactly(BOB);
    }

    @Test
    @DisplayName("并发重复提交：唯一索引拦下后用回查结果回执，仍算重放")
    void concurrentDuplicateBecomesReplay() {
        messages.failNextInsertWithIdemKeyHit(CONV, ALICE, "c-1", 7L);

        MessageService.SendOutcome outcome = service.send(cmd("你好"));

        assertThat(outcome.replayed()).isTrue();
        assertThat(outcome.message().getSeq()).isEqualTo(7L);
        assertThat(outcome.pushed()).isZero();
    }

    @Test
    @DisplayName("SYSTEM 消息：客户端来源一律 40302（它豁免好友校验，不能留绕道）")
    void clientSystemMessageIsRejected() {
        assertThatThrownBy(() -> service.send(new MessageService.SendCommand(
                CONV, ALICE, "s-1", MessageType.SYSTEM,
                "{\"action\":\"member_joined\",\"actor_id\":1}", 0, true)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.PERMISSION_DENIED);

        assertThat(conversations.nextSeqCalls).as("拒绝不能消耗序号").isZero();
        assertThat(messages.insertCalls).isZero();
        assertThat(push.recipients).isEmpty();
    }

    @Test
    @DisplayName("SYSTEM 消息：服务端来源放行（建群通知走的就是这条路）")
    void serverSystemMessageIsAccepted() {
        MessageService.SendOutcome outcome = service.send(new MessageService.SendCommand(
                CONV, ALICE, null, MessageType.SYSTEM,
                "{\"action\":\"group_created\",\"actor_id\":1001}", 0, false));

        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.message().getSeq()).isEqualTo(1L);
        assertThat(push.recipients).containsExactly(BOB);
    }

    @Test
    @DisplayName("client_msg_id 超过 64 字符回 40002（非严格模式下会被截断成同一个幂等键）")
    void tooLongClientMsgIdIsRejected() {
        assertThatThrownBy(() -> service.send(new MessageService.SendCommand(
                CONV, ALICE, "c".repeat(65), MessageType.TEXT, "{\"text\":\"hi\"}", 0, true)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);

        assertThat(conversations.nextSeqCalls).isZero();
    }

    @Test
    @DisplayName("序号撞主键：用库内最大 seq 自愈后重试，且重试成功")
    void seqCollisionTriggersSelfHealAndRetry() {
        messages.maxSeq = 41L;
        messages.failNextInsertWithPrimaryKey();

        MessageService.SendOutcome outcome = service.send(cmd("你好"));

        assertThat(conversations.raiseFloorCalls)
                .as("必须用「库内已有的最大 seq」去抬基线")
                .containsExactly(41L);
        assertThat(outcome.message().getSeq())
                .as("自愈之后应当从 42 继续，而不是继续撞")
                .isEqualTo(42L);
        assertThat(outcome.replayed()).isFalse();
    }

    @Test
    @DisplayName("单聊非好友：回 40003，且不取号、不落库、不推送")
    void directMessageToNonFriendIsRejected() {
        friendships.clear();

        assertThatThrownBy(() -> service.send(cmd("你好")))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.NOT_FRIENDS);

        assertThat(conversations.nextSeqCalls).as("被拒绝的请求不该消耗序号").isZero();
        assertThat(messages.insertCalls).isZero();
        assertThat(push.recipients).isEmpty();
    }

    @Test
    @DisplayName("被拉黑：回 40304（与「非好友」区分开，客户端应停止重试）")
    void blockedPeerIsRejectedDifferently() {
        friendships.block(ALICE, BOB);

        assertThatThrownBy(() -> service.send(cmd("你好")))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.BLOCKED_BY_PEER);
    }

    @Test
    @DisplayName("非成员：回 40303（哪怕双方是好友）")
    void nonMemberIsRejectedEvenIfFriends() {
        assertThatThrownBy(() -> service.send(
                new MessageService.SendCommand(CONV, 9999L, "c-9", MessageType.TEXT,
                        "{\"text\":\"hi\"}", 0, true)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.NOT_A_MEMBER);
    }

    @Test
    @DisplayName("群聊：不校验好友关系，只要是成员就放行")
    void groupMessageSkipsFriendshipCheck() {
        conversations.putConversation(2001L, ConvType.GROUP);
        conversations.addMember(2001L, ALICE, BOB);

        MessageService.SendOutcome outcome = service.send(
                new MessageService.SendCommand(2001L, ALICE, "g-1", MessageType.TEXT,
                        "{\"text\":\"大家好\"}", 0, true));

        assertThat(outcome.message().getSeq()).isEqualTo(1L);
        assertThat(friendships.findCalls).as("群聊不该查好友关系（500 人群 = 500 次查询）").isZero();
    }

    @Test
    @DisplayName("文字超长：回 40006 而不是截断")
    void tooLongTextIsRejectedNotTruncated() {
        String longText = "字".repeat(5001);

        assertThatThrownBy(() -> service.send(cmd(longText)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.CONTENT_TOO_LONG);
        assertThat(conversations.nextSeqCalls).isZero();
    }

    @Test
    @DisplayName("content 与 msg_type 不符：回 40009")
    void contentTypeMismatchIsRejected() {
        assertThatThrownBy(() -> service.send(new MessageService.SendCommand(
                CONV, ALICE, "c-2", MessageType.TEXT, "{\"media_id\":123}", 0, true)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.CONTENT_TYPE_MISMATCH);

        assertThatThrownBy(() -> service.send(new MessageService.SendCommand(
                CONV, ALICE, "c-3", MessageType.IMAGE, "{\"text\":\"hi\"}", 0, true)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.CONTENT_TYPE_MISMATCH);
    }

    @Test
    @DisplayName("content 不是合法 JSON：回 40002（与字段缺失是两类问题）")
    void malformedJsonIsRejected() {
        assertThatThrownBy(() -> service.send(new MessageService.SendCommand(
                CONV, ALICE, "c-4", MessageType.TEXT, "{\"text\":", 0, true)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("会话不存在：回 40402")
    void unknownConversationIsRejected() {
        assertThatThrownBy(() -> service.send(new MessageService.SendCommand(
                8888L, ALICE, "c-5", MessageType.TEXT, "{\"text\":\"hi\"}", 0, true)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    @DisplayName("大群超过写扩散阈值：只落库，不逐成员推送")
    void largeGroupSkipsWriteFanout() {
        conversations.putConversation(3001L, ConvType.GROUP);
        conversations.addMembers(3001L, 600);   // 默认阈值 500
        conversations.addMember(3001L, ALICE, 1L);

        MessageService.SendOutcome outcome = service.send(new MessageService.SendCommand(
                3001L, ALICE, "big-1", MessageType.TEXT, "{\"text\":\"大家好\"}", 0, true));

        assertThat(outcome.message().getSeq()).isEqualTo(1L);
        assertThat(outcome.pushed()).as("大群转读扩散，一次都不推").isZero();
        assertThat(push.recipients).isEmpty();
    }

    @Test
    @DisplayName("已读上报：游标超过会话最大 seq 回 40010（否则会把未产生的消息标记为已读）")
    void readCursorCannotExceedMaxSeq() {
        messages.maxSeq = 5L;

        assertThatThrownBy(() -> service.markRead(CONV, ALICE, 6L))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_CURSOR);
        assertThat(conversations.lastReadUpdates).isEmpty();
    }

    @Test
    @DisplayName("已读上报：非成员回 40303；正常路径落库")
    void readCursorPath() {
        messages.maxSeq = 5L;

        assertThatThrownBy(() -> service.markRead(CONV, 9999L, 3L))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.NOT_A_MEMBER);

        assertThat(service.markRead(CONV, ALICE, 3L)).isEqualTo(3L);
        assertThat(conversations.lastReadUpdates).containsExactly("1001:2002=3");
    }

    // ================================================================ 续传读取（DESIGN §10.2）

    @Test
    @DisplayName("续传：返回 since_seq 之后的消息（按 seq 升序），一轮补齐时时 has_more=false")
    void syncReturnsMessagesAfterCursor() {
        messages.seed(CONV, 1, 2, 3, 4, 5);

        MessageService.SyncOutcome outcome = service.sync(new MessageService.SyncCommand(
                ALICE, List.of(new MessageService.SyncCursor(CONV, 2)), 200));

        assertThat(outcome.messages()).extracting(Message::getSeq).containsExactly(3L, 4L, 5L);
        assertThat(outcome.hasMore()).isFalse();
        assertThat(outcome.truncated())
                .as("没有归档水位就不能声称数据被截断：seq 空洞是合法的，把「行不存在」当成「被删了」是假警报")
                .isFalse();
        assertThat(outcome.convsSynced()).isEqualTo(1);
        assertThat(outcome.skippedConvs()).isEmpty();
    }

    @Test
    @DisplayName("续传：多取一行判 has_more —— 取满即 true，且 true 时一定有消息可以推进游标")
    void syncReportsHasMoreByFetchingOneExtraRow() {
        messages.seed(CONV, 1, 2, 3);

        MessageService.SyncOutcome outcome = service.sync(new MessageService.SyncCommand(
                ALICE, List.of(new MessageService.SyncCursor(CONV, 0)), 2));

        assertThat(outcome.messages()).extracting(Message::getSeq).containsExactly(1L, 2L);
        assertThat(outcome.hasMore()).isTrue();
        assertThat(messages.listLimits)
                .as("多取一行是精确判断 has_more 的唯一办法（查 maxSeq 会多一次查询，且有竞态）")
                .containsExactly(3);
        assertThat(outcome.messages())
                .as("has_more=true 却一条消息都没有时，客户端无法推进游标，会死循环")
                .isNotEmpty();
    }

    @Test
    @DisplayName("续传：刚好取完时 has_more=false（多取的那一行是判据，不是数据）")
    void syncDoesNotReportHasMoreWhenThePageIsExactlyFull() {
        messages.seed(CONV, 1, 2);

        MessageService.SyncOutcome outcome = service.sync(new MessageService.SyncCommand(
                ALICE, List.of(new MessageService.SyncCursor(CONV, 0)), 2));

        assertThat(outcome.messages()).hasSize(2);
        assertThat(outcome.hasMore()).isFalse();
    }

    @Test
    @DisplayName("续传：limit=0 用服务端默认值，超限收敛到 maxPullSize（客户端传 10 万不能真的拉 10 万行）")
    void syncClampsPageSize() {
        messages.seed(CONV, 1);

        service.sync(new MessageService.SyncCommand(ALICE, List.of(new MessageService.SyncCursor(CONV, 0)), 0));
        service.sync(new MessageService.SyncCommand(ALICE, List.of(new MessageService.SyncCursor(CONV, 0)), 100_000));

        // 201 = 默认 maxPullSize(200) + 用来判 has_more 的那一行
        assertThat(messages.listLimits).containsExactly(201, 201);
    }

    @Test
    @DisplayName("续传：多个游标各自判 has_more（一个没补齐整轮就没结束），顺序按请求")
    void syncServesEveryCursorIndependently() {
        long group = 1002L;
        conversations.putConversation(group, ConvType.GROUP);
        conversations.addMember(group, ALICE);
        messages.seed(CONV, 1, 2, 3);
        messages.seed(group, 1, 2);

        MessageService.SyncOutcome outcome = service.sync(new MessageService.SyncCommand(ALICE, List.of(
                new MessageService.SyncCursor(CONV, 0),
                new MessageService.SyncCursor(group, 1)), 2));

        assertThat(outcome.messages()).extracting(Message::getConvId, Message::getSeq)
                .containsExactly(tuple(CONV, 1L), tuple(CONV, 2L), tuple(group, 2L));
        assertThat(outcome.hasMore()).as("单聊那条没补齐，整轮就没结束").isTrue();
        assertThat(outcome.convsSynced()).isEqualTo(2);
    }

    @Test
    @DisplayName("续传：非成员的会话游标被跳过，其余照常补齐（不回 40303 卡死整轮）")
    void syncSkipsCursorsTheActorCannotAccess() {
        long stranger = 7001L;
        conversations.putConversation(stranger, ConvType.GROUP);   // 会话存在，但 ALICE 不是成员
        messages.seed(CONV, 1, 2);
        messages.seed(stranger, 1);

        MessageService.SyncOutcome outcome = service.sync(new MessageService.SyncCommand(ALICE, List.of(
                new MessageService.SyncCursor(stranger, 0),
                new MessageService.SyncCursor(CONV, 0)), 10));

        assertThat(outcome.skippedConvs()).containsExactly(stranger);
        assertThat(outcome.convsSynced()).isEqualTo(1);
        assertThat(outcome.messages()).extracting(Message::getConvId)
                .as("被跳过的会话一条也不能下发").containsOnly(CONV);
        assertThat(messages.listQueries).as("被跳过的会话不该去查消息表").containsExactly(CONV);
    }

    @Test
    @DisplayName("续传：一个游标都不带 → 40001（而不是静默回一帧空结果）")
    void syncRejectsEmptyCursors() {
        messages.seed(CONV, 1);

        assertThatThrownBy(() -> service.sync(new MessageService.SyncCommand(ALICE, List.of(), 0)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.MISSING_PARAMETER);

        assertThat(messages.listQueries)
                .as("静默回空结果会让客户端以为「已经追平」，比报错难查得多")
                .isEmpty();
    }

    @Test
    @DisplayName("续传：负游标 → 40010（与已读上报同一个码：游标本身坏了）")
    void syncRejectsNegativeCursor() {
        assertThatThrownBy(() -> service.sync(new MessageService.SyncCommand(ALICE,
                List.of(new MessageService.SyncCursor(CONV, -1)), 0)))
                .isInstanceOf(TmException.class)
                .hasMessageContaining("since_seq")
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_CURSOR);
    }

    @Test
    @DisplayName("续传：同一个 conv_id 出现两次 → 40002（「按哪个游标算」没有定义，不能静默挑一个）")
    void syncRejectsDuplicateCursor() {
        assertThatThrownBy(() -> service.sync(new MessageService.SyncCommand(ALICE, List.of(
                new MessageService.SyncCursor(CONV, 0),
                new MessageService.SyncCursor(CONV, 5)), 0)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);

        assertThat(messages.listQueries)
                .as("挑一个游标继续做会静默地漏消息或重复下发，宁可不做")
                .isEmpty();
    }

    @Test
    @DisplayName("续传：游标数超过上限 → 40002 并要求分批（否则一帧能换来上万个查询）")
    void syncRejectsTooManyCursors() {
        List<MessageService.SyncCursor> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add(new MessageService.SyncCursor(9000L + i, 0));
        }

        assertThatThrownBy(() -> service.sync(new MessageService.SyncCommand(ALICE, tooMany, 0)))
                .isInstanceOf(TmException.class)
                .hasMessageContaining("分批")
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);

        assertThat(messages.listQueries).isEmpty();
    }

    @Test
    @DisplayName("续传：conv_id 非正 → 40002")
    void syncRejectsNonPositiveConvId() {
        assertThatThrownBy(() -> service.sync(new MessageService.SyncCommand(ALICE,
                List.of(new MessageService.SyncCursor(0, 0)), 0)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
    }

    // ================================================================ 测试替身
    //
    // 刻意手写而不是用 Mockito：这里的断言大多是「某个副作用有没有发生」
    // （取了几次号、插了几次库、推给了谁），手写替身能把这些计数直接暴露成字段，
    // 读起来就是一句话，而 mock 版本要靠 verify(...) 的调用次数去猜。

    private static final class SequentialIds implements IdGenerator {
        private long next = 700_000_000_000_000_000L;

        @Override
        public long nextId() {
            return ++next;
        }

        @Override
        public int nodeId() {
            return 1;
        }
    }

    private static final class RecordingPush implements MessagePushPort {
        private final List<Long> recipients = new ArrayList<>();

        @Override
        public int pushToActor(long actorId, Message message) {
            recipients.add(actorId);
            return 1;   // 假装对方都在线
        }

        @Override
        public boolean isOnline(long actorId) {
            return true;
        }

        @Override
        public int localConnectionCount() {
            return 1;
        }
    }

    private static final class FakeConversations implements ConversationRepository {
        private final Map<Long, ConvType> types = new HashMap<>();
        private final Map<Long, List<Long>> members = new HashMap<>();
        private final List<String> lastReadUpdates = new ArrayList<>();
        private final List<Long> raiseFloorCalls = new ArrayList<>();
        int nextSeqCalls;
        private long counter;
        private long floor;

        void putConversation(long convId, ConvType type) {
            types.put(convId, type);
            members.putIfAbsent(convId, new ArrayList<>());
        }

        void addMember(long convId, long... actorIds) {
            for (long id : actorIds) {
                members.computeIfAbsent(convId, k -> new ArrayList<>()).add(id);
            }
        }

        void addMembers(long convId, int count) {
            List<Long> list = members.computeIfAbsent(convId, k -> new ArrayList<>());
            for (int i = 0; i < count; i++) {
                list.add(10_000L + i);
            }
        }

        @Override
        public Optional<Conversation> findById(long convId) {
            ConvType type = types.get(convId);
            if (type == null) {
                return Optional.empty();
            }
            Conversation c = new Conversation();
            c.setId(convId);
            c.setConvType(type);
            return Optional.of(c);
        }

        @Override
        public Optional<ConversationMember> findMember(long convId, long actorId) {
            return Optional.empty();
        }

        @Override
        public boolean isMember(long convId, long actorId) {
            return members.getOrDefault(convId, List.of()).contains(actorId);
        }

        @Override
        public List<Long> listMemberIds(long convId, int limit) {
            List<Long> all = members.getOrDefault(convId, List.of());
            return all.size() <= limit ? List.copyOf(all) : List.copyOf(all.subList(0, limit));
        }

        @Override
        public void updateLastReadSeq(long convId, long actorId, long lastReadSeq) {
            lastReadUpdates.add(convId + ":" + actorId + "=" + lastReadSeq);
        }

        @Override
        public long nextSeq(long convId) {
            nextSeqCalls++;
            // 与真实实现同语义：从 max(当前值, 已抬过的基线) 之上继续，
            // 否则「自愈后重试」的断言会退化成「计数器恰好等于期望值」的巧合。
            counter = Math.max(counter, floor) + 1;
            return counter;
        }

        @Override
        public void raiseSeqFloor(long convId, long floor) {
            raiseFloorCalls.add(floor);
            this.floor = Math.max(this.floor, floor);
        }

        @Override
        public long nextSeqFromDb(long convId, long floor) {
            throw new UnsupportedOperationException("本测试不走兜底路径");
        }

        // ---- 本测试用不到的写路径 ----

        @Override
        public Optional<Conversation> findDirectByPairKey(String pairKey) {
            return Optional.empty();
        }

        @Override
        public Conversation insert(Conversation conversation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean addMember(long convId, long actorId, MemberRole role,
                                 java.time.LocalDateTime joinedAt) {
            throw new UnsupportedOperationException("本测试不走建会话的写路径");
        }

        @Override
        public void createGroup(Conversation conversation, List<ConversationMember> members) {
            throw new UnsupportedOperationException("本测试不走建群路径");
        }

        @Override
        public List<ConversationMembership> listMemberships(long actorId, int maxScan) {
            return List.of();
        }

        @Override
        public List<ConversationMember> listMembers(long convId, int limit) {
            return List.of();
        }

        @Override
        public long countMembers(long convId) {
            return members.getOrDefault(convId, List.of()).size();
        }

        // ---- 群成员管理（§4.9）：本测试只关心发消息的规则，这些一律不走 ----

        @Override
        public boolean removeMember(long convId, long actorId) {
            throw new UnsupportedOperationException("本测试不走成员管理路径");
        }

        @Override
        public boolean updateMemberRole(long convId, long actorId, MemberRole role) {
            throw new UnsupportedOperationException("本测试不走成员管理路径");
        }

        @Override
        public boolean transferOwnership(long convId, long fromActorId, long toActorId) {
            throw new UnsupportedOperationException("本测试不走成员管理路径");
        }

        @Override
        public boolean updateTitle(long convId, String title) {
            throw new UnsupportedOperationException("本测试不走成员管理路径");
        }
    }

    private static final class FakeMessages implements MessageRepository {
        private final Map<String, Message> byIdemKey = new HashMap<>();
        private final List<Message> stored = new ArrayList<>();
        /** 记录 listAfterSeq 收到的 convId 与 limit，用于断言「有没有去查」「取了多少行」。 */
        private final List<Long> listQueries = new ArrayList<>();
        private final List<Integer> listLimits = new ArrayList<>();
        long maxSeq;
        int insertCalls;
        private boolean failNextInsert;
        private Message idemConflict;
        private Message armedIdemHit;

        /** 直接往「库里」放几条消息（按给定 seq 升序），供续传用例读。 */
        void seed(long convId, long... seqs) {
            for (long seq : seqs) {
                Message m = new Message();
                m.setId(800_000L + seq);
                m.setConvId(convId);
                m.setSeq(seq);
                m.setSenderId(BOB);
                m.setMsgType(MessageType.TEXT);
                m.setContent("{\"text\":\"msg-" + seq + "\"}");
                stored.add(m);
            }
            maxSeq = Math.max(maxSeq, seqs.length == 0 ? 0 : seqs[seqs.length - 1]);
        }

        void failNextInsertWithPrimaryKey() {
            failNextInsert = true;
        }

        /**
         * 模拟「并发重复提交」：本次 insert 撞唯一索引，而回查能命中对方的记录。
         *
         * <p>注意回查命中必须在 insert 失败<b>之后</b>才生效：
         * 服务的幂等短路会在 insert 之前先查一次，那一次必须查不到，
         * 否则测的就是「短路路径」而不是「唯一索引兵底路径」了。
         */
        void failNextInsertWithIdemKeyHit(long convId, long senderId, String clientMsgId, long seq) {
            failNextInsert = true;
            Message m = new Message();
            m.setId(999_000L);
            m.setConvId(convId);
            m.setSenderId(senderId);
            m.setClientMsgId(clientMsgId);
            m.setSeq(seq);
            m.setMsgType(MessageType.TEXT);
            idemConflict = m;
        }

        @Override
        public Message insert(Message message) {
            insertCalls++;
            if (failNextInsert) {
                failNextInsert = false;
                armedIdemHit = idemConflict;
                idemConflict = null;
                throw new DuplicateKeyException("Duplicate entry");
            }
            if (message.getClientMsgId() != null) {
                Message existing = byIdemKey.get(key(message));
                if (existing != null) {
                    // 真实实现的约定：幂等键已存在时返回已存在的那条（不是新插入的）
                    return existing;
                }
                byIdemKey.put(key(message), message);
            }
            stored.add(message);
            return message;   // 与 MessageRepositoryImpl 一致：返回同一个对象
        }

        @Override
        public Optional<Message> findByIdemKey(long convId, long senderId, String clientMsgId) {
            if (armedIdemHit != null) {
                Message hit = armedIdemHit;
                armedIdemHit = null;
                return Optional.of(hit);
            }
            return Optional.ofNullable(byIdemKey.get(convId + ":" + senderId + ":" + clientMsgId));
        }

        @Override
        public long maxSeq(long convId) {
            return maxSeq;
        }

        @Override
        public Optional<Message> findLatest(long convId) {
            return stored.stream()
                    .filter(m -> m.getConvId() == convId)
                    .max(java.util.Comparator.comparingLong(Message::getSeq));
        }

        @Override
        public List<Message> listBeforeSeq(long convId, long beforeSeq, int limit) {
            return stored.stream()
                    .filter(m -> m.getConvId() == convId && (beforeSeq <= 0 || m.getSeq() < beforeSeq))
                    .sorted(java.util.Comparator.comparingLong(Message::getSeq).reversed())
                    .limit(Math.max(1, limit))
                    .toList();
        }

        @Override
        public Optional<Message> findBySeq(long convId, long seq) {
            return stored.stream().filter(m -> m.getSeq() == seq).findFirst();
        }

        @Override
        public List<Message> listAfterSeq(long convId, long sinceSeq, int limit) {
            listQueries.add(convId);
            listLimits.add(limit);
            // 与真实实现同语义：带上 convId 过滤（分片键）+ 按 seq 升序 + limit。
            return stored.stream()
                    .filter(m -> m.getConvId() == convId && m.getSeq() > sinceSeq)
                    .sorted(Comparator.comparingLong(Message::getSeq))
                    .limit(limit)
                    .toList();
        }

        @Override
        public long countByConv(long convId) {
            return stored.size();
        }

        private static String key(Message m) {
            return m.getConvId() + ":" + m.getSenderId() + ":" + m.getClientMsgId();
        }
    }

    private static final class FakeFriendships implements FriendshipRepository {
        private final Map<String, FriendshipStatus> statuses = new HashMap<>();
        int findCalls;

        void accept(long a, long b) {
            statuses.put(key(a, b), FriendshipStatus.ACCEPTED);
        }

        void block(long a, long b) {
            statuses.put(key(a, b), FriendshipStatus.BLOCKED);
        }

        void clear() {
            statuses.clear();
        }

        @Override
        public Optional<Friendship> find(long actorX, long actorY) {
            findCalls++;
            FriendshipStatus status = statuses.get(key(actorX, actorY));
            if (status == null) {
                return Optional.empty();
            }
            Friendship f = new Friendship();
            f.setActorA(Math.min(actorX, actorY));
            f.setActorB(Math.max(actorX, actorY));
            f.setStatus(status);
            f.setUpdatedAt(LocalDateTime.now());
            return Optional.of(f);
        }

        @Override
        public boolean areFriends(long actorX, long actorY) {
            return statuses.get(key(actorX, actorY)) == FriendshipStatus.ACCEPTED;
        }

        @Override
        public void save(Friendship friendship) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateStatus(long actorX, long actorY, FriendshipStatus status) {
            statuses.put(key(actorX, actorY), status);
        }

        @Override
        public List<Long> listFriendIds(long actorId, int limit) {
            return List.of();
        }

        /** 与仓储实现同口径：关系按无序对存储，参数必须归一化。 */
        private static String key(long a, long b) {
            return Math.min(a, b) + ":" + Math.max(a, b);
        }
    }
}
