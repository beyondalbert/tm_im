package com.tm.im.api.admin.view;

import java.time.Instant;

/**
 * 后台登录响应。
 *
 * <p>{@code token} 是 {@code adm_} 前缀的会话明文，<b>只在这里返回一次</b>
 * （库里只存 SHA-256）。它和用户端的 refresh_token 是同一种约定：
 * 客户端拿到之后应该存进内存（或后端的会话存储），而不是 localStorage。
 */
public record AdminLoginView(String token, Instant expiresAt, AdminView admin) {
}
