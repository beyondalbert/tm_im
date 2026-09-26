package com.tm.im.api.user.view;

/**
 * 踢人与退群的响应（03-rest-api.md §4.9 的两个 {@code DELETE}）。
 *
 * <p>两个接口共用一个视图，因为对客户端而言它们的结果是同一种：这个会话里少了一个人。
 * {@code actor_id} 在退群时就是调用者自己（客户端不必从路径里再解析一次）。
 *
 * <p>没有 {@code removed: true/false} 这种字段：目标不在群里时回的是 <b>40908</b>，
 * 所以走到这个响应体就意味着真的删掉了一行——一个恒为 true 的布尔字段只会让人以为
 * 「false 也是可能的」。
 */
public record MemberRemovedView(long convId, long actorId, long memberCount) {
}
