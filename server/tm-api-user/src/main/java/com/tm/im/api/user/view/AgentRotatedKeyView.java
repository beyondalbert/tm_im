package com.tm.im.api.user.view;

/**
 * 轮换 api_key 的响应（02-auth.md §3.2）。
 *
 * <p>只回 {@code actor_id} 与新的 {@code api_key}：轮换不改任何其它配置，
 * 把整份 Agent 视图再回一遍只会让客户端以为「配置也变了」。
 *
 * <p>{@code webhook_secret} <b>不在</b>轮换的范围内：它独立于 api_key
 * （02-auth.md §7 的安全建议里明确写了这一点），所以一次「换钥匙」不该顺手
 * 让对方的回调验签全部失败。
 */
public record AgentRotatedKeyView(long actorId, String apiKey) {
}
