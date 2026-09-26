package com.tm.im.api.user.view;

import com.tm.im.core.friend.FriendService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 好友领域的对象 → 对外视图（03-rest-api.md §3）—— <b>唯一转换点</b>。
 *
 * <p>与 {@code ActorViews} / {@code ConversationViews} 同一职责划分：
 * 领域层只给事实（谁、什么状态、什么时候），视图层决定怎么摊平成 JSON。
 * 散着写 {@code new FriendView(...)} 的失败形态是「某个接口少填了一个字段」——
 * Jackson 不报错，客户端那边只是少了个昵称。
 *
 * <p>时间换算也只在这一处（{@code tm.time.zone} → {@code Instant}）：
 * 让每个接口自己 {@code atZone} 的话，漏掉的那个接口会把时间少算 8 小时，
 * 而那个偏差只在跨时区部署或对着日志排查时才会被发现。
 */
public final class FriendViews {

    private FriendViews() {
    }

    public static FriendRequestView request(FriendService.RequestOutcome outcome, ZoneId zone) {
        return new FriendRequestView(
                outcome.friendship().getRequestId(),
                outcome.friendship().getActorA(),
                outcome.friendship().getActorB(),
                outcome.friendship().getStatus().code(),
                instant(outcome.friendship().getExpiresAt(), zone));
    }

    /**
     * 同意/拒绝的响应。
     *
     * @param status 同意是 {@code 2=ACCEPTED}；拒绝是 <b>{@code 0}</b>，含义是
     *               「这段关系已不存在」（拒绝即删除，见 {@code FriendService.reject}）
     */
    public static FriendDecisionView decision(long requestId, long actorA, long actorB,
                                              int status, LocalDateTime updatedAt,
                                              Long convId, ZoneId zone) {
        return new FriendDecisionView(requestId, actorA, actorB, status,
                instant(updatedAt, zone), convId);
    }

    public static List<FriendRequestItemView> requests(List<FriendService.RelationEntry> items,
                                                       ZoneId zone) {
        return items.stream()
                .map(entry -> new FriendRequestItemView(
                        entry.friendship().getRequestId(),
                        ActorRefView.of(entry.from()),
                        ActorRefView.of(entry.to()),
                        entry.friendship().getMessage(),
                        entry.friendship().getStatus().code(),
                        instant(entry.friendship().getCreatedAt(), zone),
                        instant(entry.friendship().getExpiresAt(), zone)))
                .toList();
    }

    public static List<FriendView> friends(List<FriendService.FriendEntry> items, ZoneId zone) {
        return items.stream()
                .map(entry -> new FriendView(
                        entry.peer().getId(),
                        entry.peer().getActorType() == null ? 0 : entry.peer().getActorType().code(),
                        entry.peer().getHandle(),
                        entry.peer().getDisplayName(),
                        entry.peer().getAvatarUrl(),
                        instant(entry.friendsSince(), zone)))
                .toList();
    }

    private static Instant instant(LocalDateTime time, ZoneId zone) {
        // 允许 null：手工插入的数据可能缺时间列，而一个空时间不该让整个列表 500。
        return Timestamps.millis(time, zone);
    }
}
