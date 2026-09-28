package com.tm.im.api.admin.view;

import java.time.Instant;

/**
 * 动态的后台视图。
 *
 * <p>{@code content} 是原始 JSON 文本（库里就是 JSON 列），而不是解析后的对象：
 * 审核要看的是<b>原文</b>，任何「顺手规整一下」的转换都可能让审核者看到的
 * 与用户看到的不是同一份东西。
 */
public record PostAdminView(long postId, long authorId, String content, int visibility,
                            int likeCount, int commentCount, Instant createdAt) {
}
