package com.tm.im.api.user.view;

/**
 * {@code POST /v1/auth/refresh} 的请求体（02-auth.md §2.3）。
 *
 * <p>只有这一个字段，没有 {@code device_id}：设备标识取自服务端自己存下的会话
 * （见 {@code AccountService.refresh}），不接受客户端在刷新时改口——
 * 否则一个被偷走的 refresh_token 就能顺手把自己伪装成受害者的常用设备。
 */
public record RefreshRequest(String refreshToken) {
}
