package com.tm.im.api.user.view;

import java.time.Instant;
import java.util.List;

/**
 * Agent 的对外表示（03-rest-api.md §7 / 02-auth.md §3.1）。
 *
 * <p><b>这个视图里没有任何凭据</b>：{@code api_key} 与 {@code webhook_secret} 只出现在
 * {@link AgentCreatedView}（创建与轮换那两次响应里），此后平台上再也取不回明文。
 * 把它们放在同一个类里加个 {@code @JsonIgnore} 也能跑，但那意味着「任何一个用到
 * {@code AgentView} 的地方」都离一次误序列化只有一步之遥 —— 分开两个类型之后，
 * 「取不回明文」这件事由类型保证。
 *
 * <p>{@code capabilities} 是数组（库里存的是 JSON 数组文本）：
 * 让客户端拿到 {@code "[\"text\"]"} 这种字符串，等于把解析责任推给每一个调用方。
 *
 * <p>{@code status} 是数字（{@code 1=ACTIVE 2=SUSPENDED}），与 {@link ActorView} 一致。
 */
public record AgentView(long actorId,
                        int actorType,
                        String handle,
                        String displayName,
                        String avatarUrl,
                        String bio,
                        int status,
                        int pushMode,
                        String endpointUrl,
                        List<String> capabilities,
                        String modelInfo,
                        int rateLimit,
                        long ownerActor,
                        Instant createdAt) {
}
