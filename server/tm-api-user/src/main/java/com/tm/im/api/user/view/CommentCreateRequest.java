package com.tm.im.api.user.view;

/**
 * §6.6 发评论的请求体。
 *
 * <p>{@code content} 是<b>字符串</b>（不是 §6.1 那种对象）：评论没有图片与外层结构，
 * 套一层 JSON 只会把一次长度校验变成一次「先取字段再校验」。
 */
public record CommentCreateRequest(String content, Long replyToCommentId) {
}
