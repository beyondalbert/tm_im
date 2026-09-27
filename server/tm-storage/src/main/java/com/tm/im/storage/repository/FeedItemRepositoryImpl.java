package com.tm.im.storage.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tm.im.domain.entity.FeedItem;
import com.tm.im.domain.repository.FeedItemRepository;
import com.tm.im.storage.batch.FeedItemBatchMapper;
import com.tm.im.storage.mapper.FeedItemMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 收件箱仓储实现。
 *
 * <p>读取只走主键 {@code (owner_id, score, post_id)}：等值前缀 + 降序范围，
 * 于是「取我一页信息流」是一次聚簇索引的范围扫描，不需要排序、也不需要回表。
 * 这也是 DDL 把 {@code score} 放进主键的唯一理由（见 {@code verify_schema.py} 的对应断言）。
 */
@Repository
public class FeedItemRepositoryImpl implements FeedItemRepository {

    private static final Logger log = LoggerFactory.getLogger(FeedItemRepositoryImpl.class);

    /**
     * 单条 INSERT 语句里最多几行。
     *
     * <p>上限来自 MySQL 的 {@code max_allowed_packet}（默认 64MB，但托管实例常调小）
     * 与「一条 SQL 里的占位符个数」——500 行 × 4 列 = 2000 个占位符，
     * 离任何一边的默认上限都还有余量。分块反而让失败的影响半径更小：
     * 一次插 5000 行时，任何一行出错都会让整条语句回滚。
     */
    private static final int INSERT_CHUNK = 500;

    private final FeedItemMapper mapper;
    private final FeedItemBatchMapper batchMapper;

    public FeedItemRepositoryImpl(FeedItemMapper mapper, FeedItemBatchMapper batchMapper) {
        this.mapper = mapper;
        this.batchMapper = batchMapper;
    }

    @Override
    @Transactional
    public void saveAll(List<FeedItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (int from = 0; from < items.size(); from += INSERT_CHUNK) {
            List<FeedItem> chunk = items.subList(from, Math.min(from + INSERT_CHUNK, items.size()));
            batchMapper.insertBatch(chunk);
        }
        log.debug("写扩散完成 rows={} chunks={}", items.size(),
                (items.size() + INSERT_CHUNK - 1) / INSERT_CHUNK);
    }

    @Override
    public List<FeedItem> pageByOwner(long ownerId, Cursor cursor, int limit) {
        return mapper.selectList(Wrappers.<FeedItem>lambdaQuery()
                .eq(FeedItem::getOwnerId, ownerId)
                .apply(cursorWhere(cursor))
                .orderByDesc(FeedItem::getScore)
                .orderByDesc(FeedItem::getPostId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    @Override
    public int deleteByPostId(long postId) {
        // idx_post (post_id)：删除动态时按它清空全部收件箱行
        return mapper.delete(Wrappers.<FeedItem>lambdaQuery().eq(FeedItem::getPostId, postId));
    }

    @Override
    public long countByOwner(long ownerId) {
        return mapper.selectCount(Wrappers.<FeedItem>lambdaQuery().eq(FeedItem::getOwnerId, ownerId));
    }

    /**
     * 游标谓词：{@code (score, post_id) < (游标)} 的展开式。
     *
     * <p>MySQL 支持行值比较（{@code (a,b) < (c,d)}），但 ShardingSphere 的 SQL 解析器
     * 对它的支持取决于版本，且失败方式是「路由/改写报错」而不是「结果不对」——
     * 展开成两个条件的析取式在任何解析器下语义都相同，代价只是多几个字符。
     */
    private static String cursorWhere(Cursor cursor) {
        if (cursor == null) {
            return "1 = 1";
        }
        return "(score < " + cursor.score() + " OR (score = " + cursor.score()
                + " AND post_id < " + cursor.postId() + "))";
    }
}
