package com.tm.im.api.user.view;

import java.util.List;

/**
 * Agent 创建（与轮换密钥）的响应 —— <b>唯一会带上明文凭据的视图</b>。
 *
 * <p>🔴 {@code apiKey} 与 {@code webhookSecret} <b>只在这一次响应里出现</b>：
 * 前者库里只存哈希（无法取回），后者虽存明文、但接口永远不再返回它。
 * 丢失只能轮换（{@code POST /v1/agents/{id}/rotate-key}）或重签。
 *
 * <p>{@code webhookSecret} 在 {@code push_mode != WEBHOOK} 时是 {@code null}
 * ——给一个永远不会被调用的密钥，只会让 Agent 方以为自己的回调通道是通的。
 */
public record AgentCreatedView(long actorId,
                               String handle,
                               int actorType,
                               String displayName,
                               int pushMode,
                               String endpointUrl,
                               List<String> capabilities,
                               String apiKey,
                               String webhookSecret,
                               java.time.Instant createdAt) {
}
