package com.tm.im.api.user.view;

/**
 * 「对方」的展示信息 —— 会话列表与单聊会话里的 {@code peer}（03-rest-api.md §4.1 / §4.3）。
 *
 * <p>刻意<b>不是</b>完整的 {@link ActorView}：peer 只出现在「我已经在跟这个人说话」的语境里，
 * 那时 {@code bio}/{@code status}/{@code relation} 都是噪声（客户端要渲染的是一行头像和名字）。
 * 而 {@code avatar_url} 必须在内——少了它，聊天列表里每个人都是同一张默认图。
 *
 * <p>字段名与 §2.2 的 Actor 完全一致（{@code actor_id}/{@code actor_type}/{@code handle}/...），
 * 所以客户端可以用同一段解析代码读这两种响应：多一个类型意味着多一处「字段名写错但没人发现」。
 */
public record PeerView(long actorId, int actorType, String handle, String displayName, String avatarUrl) {
}
