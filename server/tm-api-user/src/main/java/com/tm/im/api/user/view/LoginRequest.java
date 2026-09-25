package com.tm.im.api.user.view;

/** {@code POST /v1/auth/login} 的请求体（02-auth.md §2.2）。 */
public record LoginRequest(String handle, String password) {
}
