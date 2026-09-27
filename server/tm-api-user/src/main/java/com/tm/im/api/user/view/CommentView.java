package com.tm.im.api.user.view;

import java.time.Instant;

/**
 * §6.6 评论列表里的一项。
 *
 * <p>{@code replyToCommentId} 是「回复的那条评论」，供客户端做「A 回复 B」的前缀；
 * 它指向的评论可能已经被删除（删除评论不级联），所以客户端必须容忍查不到——
 * 服务端不回一个「已删除」的占位对象，是因为那样就要再定义一种「墓碑」形状。
 */
public record CommentView(long commentId, long postId, PlazaAuthorView author, String content,
                          Long replyToCommentId, Instant createdAt) {
}
