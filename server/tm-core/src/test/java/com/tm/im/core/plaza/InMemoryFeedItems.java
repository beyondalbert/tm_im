package com.tm.im.core.plaza;

import com.tm.im.domain.entity.FeedItem;
import com.tm.im.domain.repository.FeedItemRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * 内存收件箱替身。
 *
 * <p>与真实实现同口径的两点：写入是<b>整批</b>（{@code saveAll} 空输入不产生任何行），
 * 读取按 {@code (score, post_id)} 降序且游标严格更小。
 */
public class InMemoryFeedItems implements FeedItemRepository {

    private final List<FeedItem> rows = new ArrayList<>();

    @Override
    public void saveAll(List<FeedItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        rows.addAll(items);
    }

    @Override
    public List<FeedItem> pageByOwner(long ownerId, Cursor cursor, int limit) {
        return new ArrayList<>(rows.stream()
                .filter(row -> row.getOwnerId() == ownerId)
                .filter(row -> olderThan(row, cursor))
                .sorted((x, y) -> {
                    int byScore = Long.compare(y.getScore(), x.getScore());
                    return byScore != 0 ? byScore : Long.compare(y.getPostId(), x.getPostId());
                })
                .limit(Math.max(1, limit))
                .toList());
    }

    @Override
    public int deleteByPostId(long postId) {
        int before = rows.size();
        rows.removeIf(row -> row.getPostId() == postId);
        return before - rows.size();
    }

    @Override
    public long countByOwner(long ownerId) {
        return rows.stream().filter(row -> row.getOwnerId() == ownerId).count();
    }

    // ------------------------------------------------------------------ 测试用

    public int size() {
        return rows.size();
    }

    public List<FeedItem> all() {
        return List.copyOf(rows);
    }

    private static boolean olderThan(FeedItem row, Cursor cursor) {
        if (cursor == null) {
            return true;
        }
        if (row.getScore() != cursor.score()) {
            return row.getScore() < cursor.score();
        }
        return row.getPostId() < cursor.postId();
    }
}
