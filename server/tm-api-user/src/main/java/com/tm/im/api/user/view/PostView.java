package com.tm.im.api.user.view;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * §6.1 发布动态的响应（也是「我的某条动态」的最小形状）。
 *
 * <p>刻意<b>不带</b> {@code like_count}/{@code comment_count}/{@code liked_by_me}：
 * 文档里 §6.1 的响应就是这五个字段，而刚发出来的动态这两个计数必然是 0、
 * {@code liked_by_me} 必然是 false——放进来只会多三处「将来可能与 §6.2 漂移」的地方。
 * 客户端拿到 {@code post_id} 之后要展示计数时走 §6.2 或 §6.3。
 */
public record PostView(long postId, long authorId, JsonNode content, int visibility,
                       Instant createdAt) {
}
