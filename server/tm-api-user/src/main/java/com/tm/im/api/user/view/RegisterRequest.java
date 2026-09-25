package com.tm.im.api.user.view;

/**
 * {@code POST /v1/auth/register} 的请求体（02-auth.md §2.1）。
 *
 * <p>{@code email} 文档里标为「可选，用于找回」，但找回流程（以及
 * {@code actor} 表里存它的列）都还不存在——{@code actor} 表只有
 * id/type/handle/display_name/avatar/bio/status/created_at。
 * 所以这里<b>不接收</b>它：收下一个字段然后丢掉，比不收更糟——
 * 客户端会以为「我填了邮箱所以能找回密码」。
 *
 * <p>{@code device_id} 也不在这里，而是走 {@code X-TM-Device-Id} 头
 * （见 {@code DeviceId}）：它属于请求的元信息，与「这次登录是谁」无关。
 */
public record RegisterRequest(String handle, String password, String displayName) {
}
