package com.tm.im.core.friend;

import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.core.agent.AgentProperties;
import com.tm.im.core.conversation.ConversationProperties;
import com.tm.im.core.conversation.ConversationService;
import com.tm.im.core.conversation.InMemoryActors;
import com.tm.im.core.conversation.InMemoryConversations;
import com.tm.im.core.conversation.InMemoryMessages;
import com.tm.im.core.conversation.RecordingMessageCommands;
import com.tm.im.core.conversation.SequentialIds;
import com.tm.im.core.identity.ActorLookup;
import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.entity.Friendship;
import com.tm.im.domain.enums.ActorType;
import com.tm.im.domain.enums.FriendshipStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FriendService} 的规则（03-rest-api.md §3）。
 *
 * <p>这个类里绝大多数断言针对的都是「改坏了照样能跑」那一类规则：
 * 过期的请求被当成不存在、拒绝是删除而不是留一行、同意是幂等的、
 * 拉黑之后默认不允许再次请求、删好友是幂等的而踢群成员不是。
 * 它们都不会报错，只会让状态机慢慢漂到一个谁也说不清的状态。
 *
 * <p>刻意<b>不</b>在这里测分页的 SQL：替身的候选集与真实 MySQL 不是一回事
 * （索引、同一毫秒下的排序、游标谓词的括号都在 SQL 那一侧）。
 * 那部分由 {@code FriendHttpIT} 在真实库上覆盖，这里的替身只保证业务规则。
 */
class FriendServiceTest {

    private static final long ALICE = 1001L;
    private static final long BOB = 1002L;
    private static final long CAROL = 1003L;
    private static final long BOT = 2002L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private InMemoryFriendships friendships;
    private InMemoryActors actors;
    private InMemoryConversations conversations;
    private FriendProperties properties;
    private AgentProperties agentProperties;
    private FriendService service;

    @BeforeEach
    void setUp() {
        friendships = new InMemoryFriendships();
        actors = new InMemoryActors();
        conversations = new InMemoryConversations();
        properties = new FriendProperties();
        agentProperties = new AgentProperties();
        actors.put(ALICE, "alice");
        actors.put(BOB, "bob");
        actors.put(CAROL, "carol");
        Actor bot = actors.put(BOT, "weather_bot");
        bot.setActorType(ActorType.AGENT);

        ConversationService conversationService = new ConversationService(
                conversations,
                new InMemoryMessages(),
                actors,
                new ActorLookup(actors),
                new RecordingMessageCommands(conversations),
                new SequentialIds(),
                new ConversationProperties(),
                ZONE);
        service = new FriendService(friendships, actors, new ActorLookup(actors),
                conversationService, new SequentialIds(), properties, agentProperties, ZONE);
    }

    // ================================================================ 发起

    @Test
    @DisplayName("发起：写一行 PENDING，带 request_id 与过期时间；附言去掉首尾空白")
    void requestCreatesPendingRow() {
        FriendService.RequestOutcome outcome = service.request(ALICE, "@bob", "  你好  ");

        Friendship row = outcome.friendship();
        assertThat(row.getRequestId()).isPositive();
        assertThat(row.getActorA()).isEqualTo(ALICE);
        assertThat(row.getActorB()).isEqualTo(BOB);
        assertThat(row.getInitiator()).isEqualTo(ALICE);
        assertThat(row.getStatus()).isEqualTo(FriendshipStatus.PENDING);
        assertThat(row.getMessage()).isEqualTo("你好");
        assertThat(row.getExpiresAt()).isAfter(row.getCreatedAt());
        // 默认 7 天（tm.friend.request-expire-days）
        assertThat(row.getExpiresAt()).isEqualTo(row.getCreatedAt().plusDays(7));
        assertThat(outcome.from().getId()).isEqualTo(ALICE);
        assertThat(outcome.to().getId()).isEqualTo(BOB);
    }

    @Test
    @DisplayName("发起：不能加自己（40904），且一行都不写")
    void requestRejectsSelf() {
        assertThat(codeOf(() -> service.request(ALICE, "@alice", null)))
                .isEqualTo(ErrorCode.SELF_OPERATION);
        assertThat(friendships.size()).isZero();
    }

    @Test
    @DisplayName("发起：已是好友 → 40901；有待处理请求 → 40902（两个方向同一个码）")
    void requestRejectsExistingRelations() {
        service.request(ALICE, "@bob", null);
        assertThat(codeOf(() -> service.request(ALICE, "@bob", null)))
                .as("我发的还没被处理")
                .isEqualTo(ErrorCode.REQUEST_PENDING);
        assertThat(codeOf(() -> service.request(BOB, "@alice", null)))
                .as("对方发起的也一样：客户端该去列表里同意它，而不是再发一条")
                .isEqualTo(ErrorCode.REQUEST_PENDING);

        service.accept(BOB, friendships.get(ALICE, BOB).getRequestId());
        assertThat(codeOf(() -> service.request(ALICE, "@bob", null)))
                .isEqualTo(ErrorCode.ALREADY_FRIENDS);
    }

    @Test
    @DisplayName("发起：过期的待处理请求被覆盖（换一个 request_id），而不是回 40902")
    void expiredPendingIsOverwritten() {
        LocalDateTime longAgo = LocalDateTime.now(ZONE).minusDays(30);
        friendships.seed(550001L, ALICE, BOB, ALICE, FriendshipStatus.PENDING,
                longAgo, longAgo.plusDays(7));

        FriendService.RequestOutcome outcome = service.request(ALICE, "@bob", null);

        assertThat(outcome.friendship().getRequestId()).as("换了一个新的请求 id").isNotEqualTo(550001L);
        assertThat(outcome.friendship().getExpiresAt()).isAfter(LocalDateTime.now(ZONE));
        assertThat(friendships.size()).as("仍然是同一对人、一行").isEqualTo(1);
    }

    @Test
    @DisplayName("发起：被拉黑 → 40903；打开 allow-request-after-block 后才放行")
    void blockedRelationRejectsRequest() {
        LocalDateTime now = LocalDateTime.now(ZONE);
        // 对方拉黑了我 —— initiator 是 BOB
        friendships.seed(550002L, ALICE, BOB, BOB, FriendshipStatus.BLOCKED, now, now);

        assertThat(codeOf(() -> service.request(ALICE, "@bob", null))).isEqualTo(ErrorCode.BLOCKED);

        properties.setAllowRequestAfterBlock(true);
        assertThat(service.request(ALICE, "@bob", null).friendship().getStatus())
                .isEqualTo(FriendshipStatus.PENDING);
    }

    @Test
    @DisplayName("日配额：人类 50 / Agent 100（tm.friend.request-daily-quota / tm.agent.friend-request-daily-quota）")
    void dailyQuotaDiffersByActorType() {
        LocalDateTime now = LocalDateTime.now(ZONE);
        properties.setRequestDailyQuota(2);
        agentProperties.setFriendRequestDailyQuota(3);
        for (int i = 0; i < 2; i++) {
            friendships.seed(600000L + i, ALICE, 9000L + i, ALICE, FriendshipStatus.PENDING,
                    now, now.plusDays(7));
        }
        assertThat(codeOf(() -> service.request(ALICE, "@bob", null)))
                .as("配额按「今天发起了几条」算")
                .isEqualTo(ErrorCode.FRIEND_REQUEST_QUOTA_EXCEEDED);

        // Agent 的配额是另一档（3），所以它还发得出去
        friendships.seed(600010L, BOT, 9100L, BOT, FriendshipStatus.PENDING, now, now.plusDays(7));
        assertThat(service.request(BOT, "@bob", null).friendship().getStatus())
                .isEqualTo(FriendshipStatus.PENDING);
    }

    @Test
    @DisplayName("发起：附言超 255 字符 → 40002（不截断，截断会让发送方以为写全了）")
    void longMessageRejected() {
        assertThat(codeOf(() -> service.request(ALICE, "@bob", "x".repeat(256))))
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
        assertThat(friendships.size()).isZero();
    }

    @Test
    @DisplayName("发起：查不到的人 → 40401（handle 与 actor_id 两种写法都走同一条解析）")
    void unknownTargetRejected() {
        assertThat(codeOf(() -> service.request(ALICE, "@nobody", null)))
                .isEqualTo(ErrorCode.ACTOR_NOT_FOUND);
        assertThat(codeOf(() -> service.request(ALICE, "999999", null)))
                .isEqualTo(ErrorCode.ACTOR_NOT_FOUND);
    }

    // ================================================================ 同意 / 拒绝

    @Test
    @DisplayName("同意：变成 ACCEPTED 并自动开好单聊；重复同意幂等且 conv_id 相同")
    void acceptIsIdempotentAndOpensDirect() {
        long requestId = service.request(ALICE, "@bob", null).friendship().getRequestId();

        FriendService.AcceptOutcome first = service.accept(BOB, requestId);
        assertThat(first.friendship().getStatus()).isEqualTo(FriendshipStatus.ACCEPTED);
        assertThat(first.convId()).isPositive();
        assertThat(friendships.areFriends(ALICE, BOB)).isTrue();

        FriendService.AcceptOutcome again = service.accept(BOB, requestId);
        assertThat(again.convId()).as("幂等：重试拿到同一个会话").isEqualTo(first.convId());
        long inserts = conversations.calls().stream()
                .filter(call -> call.startsWith("insert:"))
                .count();
        assertThat(inserts)
                .as("重复同意不该再建会话（calls=%s）", conversations.calls())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("同意：附言在同意后被清掉（它不再是「请求的附言」）")
    void acceptClearsMessage() {
        long requestId = service.request(ALICE, "@bob", "你好，我是天气助手")
                .friendship().getRequestId();
        service.accept(BOB, requestId);
        assertThat(friendships.get(ALICE, BOB).getMessage()).isNull();
    }

    @Test
    @DisplayName("同意：发起人自己不能同意（40302），第三方也不能（40302）")
    void onlyTargetCanAccept() {
        long requestId = service.request(ALICE, "@bob", null).friendship().getRequestId();
        assertThat(codeOf(() -> service.accept(ALICE, requestId)))
                .as("自己同意自己发的请求不是「同意」，是自问自答")
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(codeOf(() -> service.accept(CAROL, requestId)))
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(friendships.get(ALICE, BOB).getStatus()).isEqualTo(FriendshipStatus.PENDING);
    }

    @Test
    @DisplayName("同意：不存在的请求 → 40400；已过期的 → 也是 40400（按不存在处理）")
    void acceptMissingOrExpired() {
        assertThat(codeOf(() -> service.accept(BOB, 12345L))).isEqualTo(ErrorCode.NOT_FOUND);

        LocalDateTime longAgo = LocalDateTime.now(ZONE).minusDays(30);
        friendships.seed(550003L, ALICE, BOB, ALICE, FriendshipStatus.PENDING,
                longAgo, longAgo.plusDays(7));
        assertThat(codeOf(() -> service.accept(BOB, 550003L))).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(friendships.get(ALICE, BOB).getStatus())
                .as("过期不等于删除：那一行还在，只是按不存在处理")
                .isEqualTo(FriendshipStatus.PENDING);
    }

    @Test
    @DisplayName("同意：拉黑状态下的请求不能同意（40302）")
    void acceptOnBlockedRelationRejected() {
        LocalDateTime now = LocalDateTime.now(ZONE);
        friendships.seed(550004L, ALICE, BOB, ALICE, FriendshipStatus.BLOCKED, now, now);
        assertThat(codeOf(() -> service.accept(BOB, 550004L)))
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("拒绝：整行被删掉（DESIGN §11.4：拒绝即删除），第二次拒绝 → 40400")
    void rejectDeletesRow() {
        long requestId = service.request(ALICE, "@bob", null).friendship().getRequestId();

        FriendService.RejectOutcome outcome = service.reject(BOB, requestId);
        assertThat(outcome.friendship().getRequestId()).isEqualTo(requestId);
        assertThat(friendships.size()).as("拒绝之后双方都能重新发起（没有留下「被拒过」的状态）").isZero();
        assertThat(codeOf(() -> service.reject(BOB, requestId)))
                .as("行已经没了 —— 拒绝不是幂等的（同意才是）")
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(friendships.areFriends(ALICE, BOB)).isFalse();
    }

    // ================================================================ 列表 / 删好友 / 拉黑

    @Test
    @DisplayName("列表：direction 决定「谁发起的」，pending 过滤掉过期的")
    void listRequestsFiltersDirectionAndExpiry() {
        service.request(ALICE, "@bob", "你好");
        LocalDateTime longAgo = LocalDateTime.now(ZONE).minusDays(30);
        friendships.seed(550005L, ALICE, CAROL, CAROL, FriendshipStatus.PENDING,
                longAgo, longAgo.plusDays(7));

        var outgoing = service.listRequests(ALICE, false, true, 50, null);
        assertThat(outgoing.items()).hasSize(1);
        assertThat(outgoing.items().get(0).friendship().getRequestId())
                .isEqualTo(friendships.get(ALICE, BOB).getRequestId());
        assertThat(outgoing.items().get(0).incoming()).isFalse();

        var incoming = service.listRequests(ALICE, true, true, 50, null);
        assertThat(incoming.items()).as("carol 的请求已过期，pending 视图里不该出现").isEmpty();

        var all = service.listRequests(ALICE, true, false, 50, null);
        assertThat(all.items()).as("status=all 不过滤过期").hasSize(1);
    }

    @Test
    @DisplayName("列表：好友列表给出对方与 friends_since（= 关系变成 ACCEPTED 的时刻）")
    void listFriendsReturnsPeerAndSince() {
        long requestId = service.request(ALICE, "@bob", null).friendship().getRequestId();
        service.accept(BOB, requestId);

        var page = service.listFriends(ALICE, 50, null);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).peer().getId()).isEqualTo(BOB);
        assertThat(page.items().get(0).friendsSince()).isNotNull();
        assertThat(page.nextCursor()).as("只有一项时不该给游标").isNull();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    @DisplayName("列表：第二页从游标之后继续（不重复、不漏）")
    void listFriendsPaginates() {
        for (long peer : new long[]{BOB, CAROL}) {
            long requestId = service.request(ALICE, "@" + actors.findById(peer).orElseThrow().getHandle(),
                    null).friendship().getRequestId();
            service.accept(peer, requestId);
        }
        var first = service.listFriends(ALICE, 1, null);
        assertThat(first.items()).hasSize(1);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotNull();

        var second = service.listFriends(ALICE, 1, first.nextCursor());
        assertThat(second.items()).hasSize(1);
        assertThat(second.items().get(0).peer().getId())
                .as("第二页不能重复第一页那个人")
                .isNotEqualTo(first.items().get(0).peer().getId());
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    @DisplayName("删好友：幂等（第二次 removed=false 但不报错）；不能删自己（40904）")
    void removeFriendIsIdempotent() {
        long requestId = service.request(ALICE, "@bob", null).friendship().getRequestId();
        service.accept(BOB, requestId);

        assertThat(service.removeFriend(ALICE, BOB)).isTrue();
        assertThat(service.removeFriend(ALICE, BOB))
                .as("客户端的目标状态（我们不再是好友）已经成立，报错只会让它重试")
                .isFalse();
        assertThat(friendships.areFriends(ALICE, BOB)).isFalse();
        assertThat(codeOf(() -> service.removeFriend(ALICE, ALICE)))
                .isEqualTo(ErrorCode.SELF_OPERATION);
    }

    @Test
    @DisplayName("删好友：不动会话（§3.5 历史消息仍可见）")
    void removeFriendKeepsConversation() {
        long requestId = service.request(ALICE, "@bob", null).friendship().getRequestId();
        long convId = service.accept(BOB, requestId).convId();

        service.removeFriend(ALICE, BOB);

        assertThat(conversations.findById(convId)).isPresent();
        assertThat(conversations.rows(convId)).hasSize(2);
    }

    @Test
    @DisplayName("拉黑：复用同一行改成 BLOCKED（发消息于是得到 40304）；幂等")
    void blockReusesRow() {
        long requestId = service.request(ALICE, "@bob", null).friendship().getRequestId();
        service.accept(BOB, requestId);

        service.block(ALICE, BOB);
        assertThat(friendships.get(ALICE, BOB).getStatus()).isEqualTo(FriendshipStatus.BLOCKED);
        assertThat(friendships.get(ALICE, BOB).getInitiator())
                .as("initiator 现在表示「谁拉黑了谁」，不再表示「谁先开口」")
                .isEqualTo(ALICE);
        assertThat(friendships.size()).as("拉黑不是新加一行").isEqualTo(1);

        service.block(ALICE, BOB);
        assertThat(friendships.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("解除拉黑：只删 BLOCKED 的行；当前是好友时什么都不做（不静默毁掉好友关系）")
    void unblockOnlyRemovesBlockedRow() {
        long requestId = service.request(ALICE, "@bob", null).friendship().getRequestId();
        service.accept(BOB, requestId);

        service.unblock(ALICE, BOB);
        assertThat(friendships.areFriends(ALICE, BOB))
                .as("不是拉黑状态时不能删——否则一次「解除拉黑」会悄悄删掉一段好友关系")
                .isTrue();

        service.block(ALICE, BOB);
        service.unblock(ALICE, BOB);
        assertThat(friendships.size()).isZero();
        // 幂等：再解除一次也不报错
        service.unblock(ALICE, BOB);
    }

    private static ErrorCode codeOf(Runnable action) {
        try {
            action.run();
            throw new AssertionError("期望抛 TmException，实际成功");
        } catch (TmException e) {
            return e.errorCode();
        }
    }
}
