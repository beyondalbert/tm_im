package com.tm.im.domain.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 点赞仓储（03-rest-api.md §6.5）。
 *
 * <p><b>「我赞过这条吗」与「这条有几个赞」是两个不同的问题</b>，
 * 分别由 {@link #findLikedPostIds} 与 {@code post.like_count} 回答：
 * 前者按主键 {@code (post_id, actor_id)} 点查（一页 N 条 = 一次 IN 查询），
 * 后者是冗余计数列（一页 N 条 = 0 次查询）。若两者都靠 {@code COUNT}/EXISTS 现算，
 * 信息流每页要多 40 次查询——那正是把计数列写进 DDL 的原因。
 */
public interface PostLikeRepository {

    /**
     * 点赞。
     *
     * @return false = 这个人本来就赞过（主键冲突），调用方据此回 40907 而<b>不能</b>再加计数
     */
    boolean like(long postId, long actorId, LocalDateTime now);

    /** 取消点赞。@return false = 本来就没赞过（幂等），调用方据此<b>不能</b>再减计数。 */
    boolean unlike(long postId, long actorId);

    /** 我在这批动态里赞过哪些（一页一次查询，而不是每行一次）。 */
    Set<Long> findLikedPostIds(long actorId, Collection<Long> postIds);

    /** 删除动态时清掉它的全部点赞行。返回删掉的行数。 */
    int deleteByPostId(long postId);
}
