package com.tm.im.core.conversation;

import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.domain.entity.Conversation;
import com.tm.im.domain.entity.Message;
import com.tm.im.domain.enums.ConvType;
import com.tm.im.domain.enums.MemberRole;
import com.tm.im.domain.enums.MessageType;
import com.tm.im.domain.support.PairKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ConversationService} 的规则（03-rest-api.md §4.1–§4.8）。
 *
 * <p>这里穷举的不是「能不能查到数据」（那由集成测试证明），而是三件单测才能钉住的事：
 * <ol>
 *   <li><b>每种非法输入对应哪个错误码</b>——错误码回归最难发现，接口结构没变、
 *       数据也对，只是本该 40906 的地方返回了 200；</li>
 *   <li><b>失败时有没有留下副作用</b>——建群时校验失败却已经把会话行写进去了，
 *       表现为「多了一个没有成员的群」；</li>
 *   <li><b>算法本身的边界</b>——分页游标会不会漏项/重复、未读数是不是按
 *       「最后一条消息的 seq」算的（而不是按那个平时恒为 0 的 {@code seq_counter}）。</li>
 * </ol>
 *
 * <p>另外几条断言是针对「看起来更简单的写法」的：会话列表必须<b>全量取再排序</b>
 * （先 LIMIT 再排会让最近活跃的老会话消失）、{@code has_more} 必须精确
 * （用「这一页满了吗」去猜会多拉一次空页）、已读上报必须回<b>生效后</b>的游标
 * （回请求值会让未读数凭空变大）。
 */
class ConversationServiceTest {

    private static final long ALICE = 1001L;
    private static final long BOB = 1002L;
    private static final long CAROL = 1003L;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private InMemoryConversations conversations;
    private InMemoryMessages messages;
    private InMemoryActors actors;
    private RecordingMessageCommands commands;
    private ConversationProperties properties;
    private ConversationService service;

    @BeforeEach
    void setUp() {
        conversations = new InMemoryConversations();
        messages = new InMemoryMessages();
        actors = new InMemoryActors();
        commands = new RecordingMessageCommands(conversations);
        properties = new ConversationProperties();
        actors.put(ALICE, "alice");
        actors.put(BOB, "bob");
        actors.put(CAROL, "carol");
        service = new ConversationService(conversations, messages, actors,
                new com.tm.im.core.identity.ActorLookup(actors),
                commands, new SequentialIds(), properties, ZONE);
    }

    // ================================================================ 单聊

    @Test
    @DisplayName("单聊：第一次 created=true 且写两行成员，第二次复用同一条且不再重复写成员")
    void openDirectIsIdempotent() {
        ConversationService.DirectOutcome first = service.openDirect(ALICE, "@bob");

        assertThat(first.created()).isTrue();
        assertThat(first.peer().getId()).isEqualTo(BOB);
        long convId = first.conversation().getId();
        assertThat(conversations.rows(convId)).extracting("actorId")
                .containsExactlyInAnyOrder(ALICE, BOB);

        // 反向发起（bob 找 alice）必须命中同一条：去重键是无序对，见 PairKeys
        ConversationService.DirectOutcome second = service.openDirect(BOB, "@alice");

        assertThat(second.created()).isFalse();
        assertThat(second.conversation().getId()).isEqualTo(convId);
        assertThat(conversations.rows(convId)).as("复用已有会话不该写出第三行成员").hasSize(2);
    }

    @Test
    @DisplayName("单聊：去重键是 min_max（与 PairKeys 一致），不是「谁先发起」")
    void openDirectUsesUnorderedPairKey() {
        service.openDirect(CAROL, "@alice");

        Conversation created = conversations.findById(service.openDirect(ALICE, "@carol")
                .conversation().getId()).orElseThrow();
        assertThat(created.getPairKey()).isEqualTo(PairKeys.directConversationKey(ALICE, CAROL));
        assertThat(created.getPairKey()).isEqualTo(ALICE + "_" + CAROL);
    }

    @Test
    @DisplayName("单聊：peer 也可以用 actor_id 写（纯数字），两种写法都支持")
    void openDirectAcceptsActorId() {
        assertThat(service.openDirect(ALICE, String.valueOf(BOB)).peer().getId()).isEqualTo(BOB);
        assertThat(service.openDirect(ALICE, "@bob").created()).isFalse();
    }

    @Test
    @DisplayName("单聊：不能和自己建（40904），也不会留下会话行")
    void openDirectRejectsSelf() {
        assertThatThrownBy(() -> service.openDirect(ALICE, "@alice"))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.SELF_OPERATION);

        assertThatThrownBy(() -> service.openDirect(ALICE, String.valueOf(ALICE)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.SELF_OPERATION);

        assertThat(conversations.calls()).as("校验失败必须发生在写入之前").isEmpty();
    }

    @Test
    @DisplayName("单聊：不存在的 handle 回 40401；不带 @ 的写法回 40002（不猜它是 handle 还是 id）")
    void openDirectRejectsUnknownOrAmbiguousPeer() {
        assertThatThrownBy(() -> service.openDirect(ALICE, "@nobody"))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.ACTOR_NOT_FOUND);

        assertThatThrownBy(() -> service.openDirect(ALICE, "bob"))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("单聊：并发建同一条时会撞 uk_pair_key，撞了要复用对方刚建的那条")
    void openDirectRecoversFromConcurrentCreate() {
        // 先造出「对方已经建好了」的状态，再让本次 insert 撞唯一键
        Conversation existing = new Conversation();
        existing.setId(999L);
        existing.setConvType(ConvType.DIRECT);
        existing.setPairKey(PairKeys.directConversationKey(ALICE, BOB));
        existing.setCreatedAt(LocalDateTime.of(2026, 1, 1, 8, 0));
        conversations.put(existing);
        conversations.failNextInsertWithDuplicatePairKey = true;

        ConversationService.DirectOutcome outcome = service.openDirect(ALICE, "@bob");

        assertThat(outcome.created()).as("撞了唯一键说明是并发，不是新建").isFalse();
        assertThat(outcome.conversation().getId()).isEqualTo(999L);
    }

    // ================================================================ 建群

    @Test
    @DisplayName("建群：写 1 行会话 + N 行成员，群主是 OWNER，其余是 MEMBER")
    void createGroupWritesOwnerAndMembers() {
        ConversationService.GroupOutcome outcome = service.createGroup(ALICE, "  天气讨论组  ",
                List.of("@bob", "@carol"));

        long convId = outcome.conversation().getId();
        assertThat(outcome.conversation().getTitle()).as("群名要 trim").isEqualTo("天气讨论组");
        assertThat(outcome.conversation().getOwnerActor()).isEqualTo(ALICE);
        assertThat(outcome.conversation().getPairKey()).as("群聊不该有单聊去重键").isNull();
        assertThat(conversations.rows(convId)).hasSize(3);
        assertThat(conversations.member(convId, ALICE).getRole()).isEqualTo(MemberRole.OWNER);
        assertThat(conversations.member(convId, BOB).getRole()).isEqualTo(MemberRole.MEMBER);
        assertThat(conversations.calls()).as("会话行与成员行必须一次交给仓储（原子）")
                .contains("createGroup:" + convId + ":3");
    }

    @Test
    @DisplayName("建群：members 里含自己会被忽略、重复的 handle 会去重（都不报错）")
    void createGroupDeduplicatesAndIgnoresSelf() {
        ConversationService.GroupOutcome outcome = service.createGroup(ALICE, "G",
                List.of("@alice", "@bob", "@bob"));

        assertThat(conversations.rows(outcome.conversation().getId())).hasSize(2);
    }

    @Test
    @DisplayName("建群：members 空 / 只有自己 → 40001、40002；超上限 → 40906")
    void createGroupValidatesMemberSet() {
        assertThatThrownBy(() -> service.createGroup(ALICE, "G", List.of()))
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.MISSING_PARAMETER);

        assertThatThrownBy(() -> service.createGroup(ALICE, "G", List.of("@alice")))
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);

        properties.setMaxGroupMembers(3);
        assertThatThrownBy(() -> service.createGroup(ALICE, "G",
                List.of("@bob", "@carol", "@bob")))
                .as("按请求里的个数粗筛（去重前）")
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.GROUP_MEMBER_LIMIT);
    }

    @Test
    @DisplayName("建群：群名为空 / 超长都要拒（截断会让客户端回显的群名与库里不一致）")
    void createGroupValidatesTitle() {
        assertThatThrownBy(() -> service.createGroup(ALICE, "   ", List.of("@bob")))
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.MISSING_PARAMETER);

        assertThatThrownBy(() -> service.createGroup(ALICE, "长".repeat(129), List.of("@bob")))
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("建群：成员里有查不到的人 → 40401，且一行都不写（否则会留下半个群）")
    void createGroupValidatesEverythingBeforeWriting() {
        assertThatThrownBy(() -> service.createGroup(ALICE, "G", List.of("@bob", "@nobody")))
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.ACTOR_NOT_FOUND);

        assertThat(conversations.calls()).as("解析成员失败时不该有任何写入").isEmpty();
    }

    @Test
    @DisplayName("建群：写一条服务端产生的 SYSTEM 消息（fromClient=false）")
    void createGroupAnnouncesWithServerSideSystemMessage() {
        ConversationService.GroupOutcome outcome = service.createGroup(ALICE, "G", List.of("@bob"));

        assertThat(outcome.systemMessageSent()).isTrue();
        assertThat(commands.sent()).hasSize(1);
        var sent = commands.sent().get(0);
        assertThat(sent.convId()).isEqualTo(outcome.conversation().getId());
        assertThat(sent.senderId()).isEqualTo(ALICE);
        assertThat(sent.msgType()).isEqualTo(MessageType.SYSTEM);
        assertThat(sent.fromClient()).as("服务端自己产生的消息，不是客户端来源").isFalse();
        assertThat(sent.contentJson()).contains("group_created");
    }

    @Test
    @DisplayName("建群：系统消息写失败不回滚、也不让请求失败（它是通知，不是事实）")
    void createGroupSurvivesSystemMessageFailure() {
        commands.nextSendFailure = new IllegalStateException("DB 挂了");

        ConversationService.GroupOutcome outcome = service.createGroup(ALICE, "G", List.of("@bob"));

        assertThat(outcome.systemMessageSent()).isFalse();
        assertThat(conversations.rows(outcome.conversation().getId())).as("群必须还在").hasSize(2);
    }

    // ================================================================ 会话列表

    @Test
    @DisplayName("列表：按最近活跃倒序（不是按 joined_at），未读数按「最后一条的 seq」算")
    void listSortsByActivityNotByJoinTime() {
        long old = seedConversation(ConvType.DIRECT, BOB, 1L, "201_1");
        long recent = seedConversation(ConvType.DIRECT, CAROL, 2L, "1_201");
        // old 先建的（join 序号小），但 recent 才是最近说话的
        messages.put(old, 1, BOB, "很久以前", LocalDateTime.of(2026, 1, 1, 8, 0));
        messages.put(old, 2, ALICE, "更久以前", LocalDateTime.of(2026, 1, 1, 9, 0));
        messages.put(recent, 1, CAROL, "刚刚", LocalDateTime.of(2026, 3, 1, 8, 0));
        conversations.updateLastReadSeq(recent, ALICE, 0);

        ConversationService.Page<ConversationService.ConversationSummary> page =
                service.listConversations(ALICE, 50, null);

        assertThat(page.items()).extracting(s -> s.conversation().getId())
                .containsExactly(recent, old);
        ConversationService.ConversationSummary first = page.items().get(0);
        assertThat(first.lastSeq()).isEqualTo(1L);
        assertThat(first.unreadCount()).isEqualTo(1L);
        assertThat(first.peer().getId()).isEqualTo(CAROL);
        assertThat(first.lastMessage().getContent()).contains("刚刚");
        assertThat(first.updatedAt()).isEqualTo(LocalDateTime.of(2026, 3, 1, 8, 0));
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("列表：没有消息的会话也要在列表里（用会话创建时间排序、未读为 0）")
    void listKeepsEmptyConversations() {
        long empty = seedConversation(ConvType.DIRECT, BOB, 1L, "1_201");

        ConversationService.Page<ConversationService.ConversationSummary> page =
                service.listConversations(ALICE, 50, null);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).conversation().getId()).isEqualTo(empty);
        assertThat(page.items().get(0).lastSeq()).isZero();
        assertThat(page.items().get(0).unreadCount()).isZero();
        assertThat(page.items().get(0).lastMessage()).isNull();
        assertThat(page.items().get(0).updatedAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 8, 0));
    }

    @Test
    @DisplayName("列表：未读数 = last_seq − last_read_seq（群聊也要对）")
    void listComputesUnreadCount() {
        long group = seedConversation(ConvType.GROUP, 0L, 3L, "1_3");
        for (int seq = 1; seq <= 5; seq++) {
            messages.put(group, seq, BOB, "m" + seq, LocalDateTime.of(2026, 2, 1, 8, seq));
        }
        conversations.updateLastReadSeq(group, ALICE, 3);

        ConversationService.ConversationSummary summary =
                service.listConversations(ALICE, 50, null).items().get(0);

        assertThat(summary.lastSeq()).isEqualTo(5L);
        assertThat(summary.lastReadSeq()).isEqualTo(3L);
        assertThat(summary.unreadCount()).isEqualTo(2L);
        assertThat(summary.memberCount()).as("群聊成员数是精确值").isEqualTo(3L);
        assertThat(summary.peer()).as("群聊没有 peer").isNull();
    }

    @Test
    @DisplayName("列表：分页不漏不重——游标语义是「比这一项更旧」")
    void listPagesWithoutGapsOrDuplicates() {
        long a = seedConversation(ConvType.DIRECT, BOB, 1L, "1_2");
        long b = seedConversation(ConvType.DIRECT, CAROL, 2L, "1_3");
        long c = seedConversation(ConvType.GROUP, 0L, 3L, "1_4");
        messages.put(a, 1, BOB, "a", LocalDateTime.of(2026, 1, 1, 8, 0));
        messages.put(b, 1, CAROL, "b", LocalDateTime.of(2026, 1, 1, 9, 0));
        messages.put(c, 1, BOB, "c", LocalDateTime.of(2026, 1, 1, 10, 0));

        ConversationService.Page<ConversationService.ConversationSummary> first =
                service.listConversations(ALICE, 2, null);
        assertThat(first.items()).extracting(s -> s.conversation().getId()).containsExactly(c, b);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotNull();

        ConversationService.Page<ConversationService.ConversationSummary> second =
                service.listConversations(ALICE, 2, first.nextCursor());
        assertThat(second.items()).extracting(s -> s.conversation().getId()).containsExactly(a);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    @DisplayName("列表：活跃时间相同的两个会话靠 conv_id 定序（只有时间的话分页会重复或漏项）")
    void listBreaksTiesByConversationId() {
        long small = seedConversation(ConvType.DIRECT, BOB, 1L, "1_2");
        long big = seedConversation(ConvType.DIRECT, CAROL, 2L, "1_3");
        LocalDateTime same = LocalDateTime.of(2026, 1, 1, 8, 0);
        messages.put(small, 1, BOB, "x", same);
        messages.put(big, 1, CAROL, "y", same);

        ConversationService.Page<ConversationService.ConversationSummary> first =
                service.listConversations(ALICE, 1, null);
        assertThat(first.items().get(0).conversation().getId()).isEqualTo(big);

        ConversationService.Page<ConversationService.ConversationSummary> second =
                service.listConversations(ALICE, 1, first.nextCursor());
        assertThat(second.items()).extracting(s -> s.conversation().getId()).containsExactly(small);
    }

    @Test
    @DisplayName("列表：拿消息游标来翻会话列表 → 40010（两种游标互不通用，不猜）")
    void listRejectsMessageCursor() {
        seedConversation(ConvType.DIRECT, BOB, 1L, "1_2");

        assertThatThrownBy(() -> service.listConversations(ALICE, 50,
                PageCursors.encodeMessage(7)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_CURSOR);
    }

    // ================================================================ 会话详情

    @Test
    @DisplayName("详情：成员带资料、群主可见、未读数与成员数都要给")
    void detailIncludesMembersAndCounts() {
        long group = seedConversation(ConvType.GROUP, 0L, 3L, "1_4");
        messages.put(group, 1, BOB, "hi", LocalDateTime.of(2026, 2, 1, 8, 0));
        conversations.updateLastReadSeq(group, ALICE, 1);

        ConversationService.ConversationDetail detail = service.detail(ALICE, group);

        assertThat(detail.memberCount()).isEqualTo(3L);
        assertThat(detail.members()).extracting(m -> m.actor().getHandle())
                .containsExactlyInAnyOrder("alice", "bob", "carol");
        assertThat(detail.lastSeq()).isEqualTo(1L);
        assertThat(detail.me().member().getLastReadSeq()).isEqualTo(1L);
        assertThat(detail.unreadCount()).isZero();
    }

    @Test
    @DisplayName("详情：会话不存在回 40402，不是成员回 40303（两个码各有用途）")
    void detailDistinguishesMissingFromForbidden() {
        long group = seedConversation(ConvType.GROUP, 0L, 2L, "1_3");

        assertThatThrownBy(() -> service.detail(ALICE, 12345L))
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND);

        assertThatThrownBy(() -> service.detail(CAROL, group))
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.NOT_A_MEMBER);
    }

    // ================================================================ 消息

    @Test
    @DisplayName("历史：倒序（最新在前）、has_more 精确、最后一页的 next_cursor 是 null")
    void historyPagesBackwardsExactly() {
        long conv = seedConversation(ConvType.DIRECT, BOB, 1L, "1_2");
        for (int seq = 1; seq <= 5; seq++) {
            messages.put(conv, seq, BOB, "m" + seq, LocalDateTime.of(2026, 2, 1, 8, 0, seq));
        }

        ConversationService.Page<Message> first = service.history(ALICE, conv, 2, null);
        assertThat(first.items()).extracting(Message::getSeq).containsExactly(5L, 4L);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotNull();

        ConversationService.Page<Message> second = service.history(ALICE, conv, 2, first.nextCursor());
        assertThat(second.items()).extracting(Message::getSeq).containsExactly(3L, 2L);
        assertThat(second.hasMore()).isTrue();

        ConversationService.Page<Message> third = service.history(ALICE, conv, 2, second.nextCursor());
        assertThat(third.items()).extracting(Message::getSeq).containsExactly(1L);
        assertThat(third.hasMore()).isFalse();
        assertThat(third.nextCursor()).isNull();

        // 空会话（或游标已经比最早的还早）不该回游标：回了会让客户端死循环地拉空页
        long empty = seedConversation(ConvType.DIRECT, CAROL, 2L, "1_3");
        ConversationService.Page<Message> none = service.history(ALICE, empty, 2, third.nextCursor());
        assertThat(none.items()).isEmpty();
        assertThat(none.hasMore()).isFalse();
        assertThat(none.nextCursor()).isNull();
    }

    @Test
    @DisplayName("历史：非成员回 40303，会话不存在回 40402（与发消息同一套判断）")
    void historyRequiresMembership() {
        long conv = seedConversation(ConvType.DIRECT, BOB, 1L, "1_2");

        assertThatThrownBy(() -> service.history(CAROL, conv, 10, null))
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.NOT_A_MEMBER);

        assertThatThrownBy(() -> service.history(ALICE, 999_999L, 10, null))
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    @DisplayName("增量：seq 升序、不含 since_seq 本身、latest_seq 是服务端当前最大 seq")
    void incrementalReturnsAscendingPage() {
        long conv = seedConversation(ConvType.DIRECT, BOB, 1L, "1_2");
        for (int seq = 1; seq <= 5; seq++) {
            messages.put(conv, seq, BOB, "m" + seq, LocalDateTime.of(2026, 2, 1, 8, 0, seq));
        }

        ConversationService.IncrementalOutcome first = service.incremental(ALICE, conv, 2, 2);
        assertThat(first.items()).extracting(Message::getSeq).containsExactly(3L, 4L);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.latestSeq()).as("latest_seq 是服务端当前最大 seq，不是这一页的最后一条")
                .isEqualTo(5L);

        ConversationService.IncrementalOutcome second = service.incremental(ALICE, conv, 4, 2);
        assertThat(second.items()).extracting(Message::getSeq).containsExactly(5L);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.latestSeq()).isEqualTo(5L);
    }

    @Test
    @DisplayName("增量：since_seq 为负回 40010（不能宽容成 0，那会让客户端以为自己在续传）")
    void incrementalRejectsNegativeSinceSeq() {
        long conv = seedConversation(ConvType.DIRECT, BOB, 1L, "1_2");

        assertThatThrownBy(() -> service.incremental(ALICE, conv, -1, 10))
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_CURSOR);
    }

    @Test
    @DisplayName("已读：回的是生效后的游标（上报更小的值不会让未读数变大）")
    void markReadReturnsEffectiveCursor() {
        long conv = seedConversation(ConvType.DIRECT, BOB, 1L, "1_2");
        for (int seq = 1; seq <= 5; seq++) {
            messages.put(conv, seq, BOB, "m" + seq, LocalDateTime.of(2026, 2, 1, 8, 0, seq));
        }

        ConversationService.ReadOutcome first = service.markRead(ALICE, conv, 3);
        assertThat(first.lastReadSeq()).isEqualTo(3L);
        assertThat(first.unreadCount()).isEqualTo(2L);

        // 乱序到达的旧请求（上报 1）：游标只前进，响应必须回 3 而不是 1
        ConversationService.ReadOutcome stale = service.markRead(ALICE, conv, 1);
        assertThat(stale.lastReadSeq()).as("回请求值会让客户端显示 4 条未读，而实际是 2 条")
                .isEqualTo(3L);
        assertThat(stale.unreadCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("已读：非成员回 40303（校验在 MessageService 里，本类只是转调）")
    void markReadRequiresMembership() {
        long conv = seedConversation(ConvType.DIRECT, BOB, 1L, "1_2");

        assertThatThrownBy(() -> service.markRead(CAROL, conv, 1))
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.NOT_A_MEMBER);
    }

    // ================================================================ 辅助

    /**
     * 造一个「ALICE 已经在里面」的会话。
     *
     * @param peerId 单聊的对方；群聊传 0
     * @param memberCount 成员数（单聊忽略；群聊为 ALICE+BOB+CAROL… 的个数）
     * @param pairKey 单聊去重键（用调用方给的字面量，避免测试自己重算一遍被测逻辑）
     */
    private long seedConversation(ConvType type, long peerId, long memberCount, String pairKey) {
        long convId = 100_000L + conversations.calls().size() + peerId + memberCount;
        Conversation conversation = new Conversation();
        conversation.setId(convId);
        conversation.setConvType(type);
        conversation.setPairKey(type == ConvType.DIRECT ? pairKey : null);
        conversation.setTitle(type == ConvType.GROUP ? "G" + convId : null);
        conversation.setCreatedAt(LocalDateTime.of(2026, 1, 1, 8, 0));
        conversations.put(conversation);
        conversations.putMember(convId, ALICE, MemberRole.MEMBER, 0L);
        if (type == ConvType.DIRECT) {
            conversations.putMember(convId, peerId, MemberRole.MEMBER, 0L);
        } else {
            conversations.putMember(convId, BOB, MemberRole.OWNER, 0L);
            if (memberCount >= 3) {
                conversations.putMember(convId, CAROL, MemberRole.MEMBER, 0L);
            }
            for (long i = 3; i < memberCount; i++) {
                long extra = 1200L + i;
                actors.put(extra, "user" + extra);
                conversations.putMember(convId, extra, MemberRole.MEMBER, 0L);
            }
        }
        return convId;
    }
}
