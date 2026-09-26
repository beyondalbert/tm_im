package com.tm.im.api.user.view;

/**
 * 改角色／转让群主的响应（03-rest-api.md §4.9 的 {@code PATCH .../members/{actor_id}}）。
 *
 * <p>{@code role} 是 {@code actor_id} <b>生效后</b>的角色；{@code owner_actor} 是生效后的群主。
 * 两个字段一起回的原因很具体：转让群主时<b>被改的不止目标一行</b>——调用者自己从 OWNER
 * 降为 ADMIN，而客户端只有拿到 {@code owner_actor} 才能知道「我现在不是群主了」，
 * 否则它刷新前还会继续显示群主专属的按钮（点了只会拿到 40305）。
 *
 * <p>回的是<b>库里的结果</b>而不是请求里的入参。两者在正常情况下相同，
 * 在「并发下另一个人先把群主转走了」时不同——那种情况下这个请求会回 40305，
 * 走不到这里，所以这里回的一定是真实状态（与 §4.8 已读上报回生效值同一条理由）。
 */
public record MemberRoleView(long convId,
                             long actorId,
                             int role,
                             long ownerActor,
                             long memberCount) {
}
