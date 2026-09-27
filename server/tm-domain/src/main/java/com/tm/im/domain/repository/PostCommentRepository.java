package com.tm.im.domain.repository;

import com.tm.im.domain.entity.PostComment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 评论仓储（03-rest-api.md §6.6）。
 *
 * <p>与点赞一样，条数由 {@code post.comment_count} 回答，这里只负责明细。
 */
public interface PostCommentRepository {

    /**
     * 分页游标：{@code (created_at, id)}。
     *
     * <p>评论按<b>时间正序</b>下发（与聊天记录同向：一条讨论线是从上往下读的），
     * 因此游标谓词是「比这一条更新」，与信息流的「更旧」相反。
     * 方向写反的表现是「第二页返回第一页的内容」，而两页看起来都正常。
     */
    record Cursor(LocalDateTime createdAt, long commentId) {
    }

    void insert(PostComment comment);

    Optional<PostComment> findById(long commentId);

    /** 按动态拉评论，时间正序。{@code cursor} 为 null 表示第一页。 */
    List<PostComment> pageByPost(long postId, Cursor cursor, int limit);

    /** 删除一条评论；返回是否真的删掉了一行（调用方据此决定要不要减计数）。 */
    boolean deleteById(long commentId);

    /** 删除动态时清掉它的全部评论行。返回删掉的行数。 */
    int deleteByPostId(long postId);
}
