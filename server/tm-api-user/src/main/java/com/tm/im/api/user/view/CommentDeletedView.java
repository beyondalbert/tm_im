package com.tm.im.api.user.view;

/**
 * §6.6 删除评论的响应。
 *
 * <p>与 {@link PostDeletedView} 同一形状，不合并成一个「通用删除视图」：
 * 两者的 {@code id} 含义不同（动态 id 与评论 id），而一个泛型 record 会让
 * 「把 post_id 当成 comment_id 返回」这种错误在编译期就不再被拦住。
 */
public record CommentDeletedView(long commentId, boolean deleted) {
}
