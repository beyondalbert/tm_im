package com.tm.im.core.plaza;

import com.tm.im.domain.entity.PostLike;
import com.tm.im.domain.repository.PostLikeRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;

/**
 * 内存点赞替身。
 *
 * <p>与真实实现同口径的<b>可观察行为</b>：重复点赞返回 false（真实现是靠主键冲突
 * 捕获后返回 false——替身直接查重，因为这里的断言针对的是调用方拿 false 之后做什么，
 * 而不是 MyBatis 会不会抛 DuplicateKeyException，后者由 {@code PlazaHttpIT} 覆盖）。
 */
public class InMemoryPostLikes implements PostLikeRepository {

    private final List<PostLike> rows = new ArrayList<>();

    @Override
    public boolean like(long postId, long actorId, LocalDateTime now) {
        if (contains(postId, actorId)) {
            return false;
        }
        PostLike row = new PostLike();
        row.setPostId(postId);
        row.setActorId(actorId);
        row.setCreatedAt(now);
        rows.add(row);
        return true;
    }

    @Override
    public boolean unlike(long postId, long actorId) {
        return rows.removeIf(row -> row.getPostId() == postId && row.getActorId() == actorId);
    }

    @Override
    public Set<Long> findLikedPostIds(long actorId, Collection<Long> postIds) {
        Set<Long> out = new LinkedHashSet<>();
        for (PostLike row : rows) {
            if (row.getActorId() == actorId && postIds.contains(row.getPostId())) {
                out.add(row.getPostId());
            }
        }
        return out;
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

    private boolean contains(long postId, long actorId) {
        return rows.stream().anyMatch(row -> row.getPostId() == postId && row.getActorId() == actorId);
    }
}
