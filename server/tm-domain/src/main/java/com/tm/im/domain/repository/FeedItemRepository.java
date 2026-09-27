package com.tm.im.domain.repository;

import com.tm.im.domain.entity.FeedItem;

import java.util.List;

/**
 * 收件箱式时间线仓储（DESIGN §11.3 的写扩散产物）。
 *
 * <p>这张表是<b>纯派生数据</b>：它只由 {@code post} 推导而来，任何一行都可以
 * 由「谁是谁的好友」重建。因此它不需要与 post 保持强一致——扩散失败时最坏的结果是
 * 「这条动态没出现在某些人的信息流里」，而那比「发帖失败」轻得多（见
 * {@code PlazaService} 里扩散跑在独立线程池、失败只记日志的取舍）。
 *
 * <p>反过来说，它是<b>可写不可乱写</b>的：一行 {@code feed_item} 的语义只有
 * 「owner 应该在信息流里看到 post」这一条，任何额外的含义（比如拿它当已读标记）
 * 都会让「删除动态时按 post_id 清一遍」这条清理逻辑失效。
 */
public interface FeedItemRepository {

    /**
     * 分页游标：{@code (score, post_id)}——与主键 {@code (owner_id, score, post_id)} 同形。
     *
     * <p>带上 {@code post_id} 不是可选的：{@code score} 的时间位只有秒级精度
     * （见 {@code FeedScores}），同一秒内两个好友各发一条时 score 可能完全相同，
     * 只按 score 分页会在这一批里重复或漏项。
     */
    record Cursor(long score, long postId) {
    }

    /**
     * 批量写入（写扩散）。
     *
     * <p>{@code items} 为空时什么都不做——一次「好友为零」的发帖不该产生任何 SQL。
     */
    void saveAll(List<FeedItem> items);

    /** 某个收件人的信息流，按 {@code score DESC} 取（走主键聚簇索引，不额外排序）。 */
    List<FeedItem> pageByOwner(long ownerId, Cursor cursor, int limit);

    /** 清理某条动态的全部收件箱行（删除动态、或作者被删号时调用）。返回删掉的行数。 */
    int deleteByPostId(long postId);

    /** 收件箱里的行数（仅用于测试与运维核对；不是热路径）。 */
    long countByOwner(long ownerId);
}
