package com.tm.im.core.plaza;

import com.tm.im.domain.entity.PostComment;
import com.tm.im.domain.repository.PostCommentRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 内存评论替身。
 *
 * <p>与真实实现同口径：按 {@code (created_at, id)} <b>正序</b>下发，
 * 游标谓词是「比游标更新」（与信息流相反）。
 */
public class InMemoryPostComments implements PostCommentRepository {

    private final List<PostComment> rows = new ArrayList<>();

    @Override
    public void insert(PostComment comment) {
        if (comment.getId() == null) {
            throw new IllegalArgumentException("评论缺少 id（必须由调用方生成）");
        }
        rows.add(comment);
    }

    @Override
    public Optional<PostComment> findById(long commentId) {
        return rows.stream().filter(row -> row.getId() == commentId).findFirst();
    }

    @Override
    public List<PostComment> pageByPost(long postId, Cursor cursor, int limit) {
        return new ArrayList<>(rows.stream()
                .filter(row -> row.getPostId() == postId)
                .filter(row -> newerThan(row, cursor))
                .sorted((x, y) -> {
                    int byTime = x.getCreatedAt().compareTo(y.getCreatedAt());
                    return byTime != 0 ? byTime : Long.compare(x.getId(), y.getId());
                })
                .limit(Math.max(1, limit))
                .toList());
    }

    @Override
    public boolean deleteById(long commentId) {
        return rows.removeIf(row -> row.getId() == commentId);
    }

    @Override
    public int deleteByPostId(long postId) {
        int before = rows.size();
        rows.removeIf(row -> row.getPostId() == postId);
        return before - rows.size();
    }

    // ------------------------------------------------------------------ 测试用

    public int size() {
        return rows.size();
    }

    private static boolean newerThan(PostComment row, Cursor cursor) {
        if (cursor == null) {
            return true;
        }
        int byTime = row.getCreatedAt().compareTo(cursor.createdAt());
        return byTime > 0 || (byTime == 0 && row.getId() > cursor.commentId());
    }
}
