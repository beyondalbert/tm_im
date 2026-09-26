package com.tm.im.api.user.view;

/**
 * §3.6 拉黑/解除拉黑的响应。
 *
 * <p>{@code blocked} 是<b>生效后</b>的状态：拉黑接口回 {@code true}、
 * 解除接口回 {@code false}。客户端据此切换按钮，而不是靠自己记住刚才点了哪个
 * （重试与并发下「上一次点的是什么」并不可靠）。
 */
public record FriendBlockView(long actorId, long targetId, boolean blocked) {
}
