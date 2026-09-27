package com.tm.im.core.plaza;

import com.tm.im.domain.entity.Post;
import com.tm.im.domain.enums.Visibility;
import com.tm.im.domain.repository.PostRepository;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存动态仓储替身。
 *
 * <p><b>刻意复刻真实实现的四个行为</b>，否则测试会在错误的前提下变绿：
 * <ol>
 *   <li>主键重复 → {@link DuplicateKeyException}（服务层的并发重放分支靠它）；</li>
 *   <li>幂等键 {@code (author_id, client_post_id)} 冲突 → {@link DuplicateKeyException}
 *       （同 {@code uk_post_idem}）；</li>
 *   <li>分页的游标是<b>严格「比游标更旧」</b>，且 {@code (created_at, id)} 作为一对比较；</li>
 *   <li>{@code addLikeCount}/{@code addCommentCount} 是「在原值上加减」而不是「写入一个值」。</li>
 * </ol>
 *
 * <p>它证明不了 SQL 层面的东西（索引是否被用上、括号是否漏了、
 * ShardingSphere 会不会改写）——那些由 {@code PlazaHttpIT} 在真实 MySQL 上覆盖。
 */
public class InMemoryPosts implements PostRepository {

    private final Map<Long, Post> rows = new LinkedHashMap<>();

    @Override
    public void insert(Post post) {
        if (post.getId() == null) {
            throw new IllegalArgumentException("动态缺少 id（必须由调用方生成）");
        }
        if (rows.containsKey(post.getId())) {
            throw new DuplicateKeyException("替身：主键重复 id=" + post.getId());
        }
        if (post.getClientPostId() != null) {
            boolean duplicated = rows.values().stream().anyMatch(row ->
                    row.getAuthorId().equals(post.getAuthorId())
                            && post.getClientPostId().equals(row.getClientPostId()));
            if (duplicated) {
                throw new DuplicateKeyException("替身：uk_post_idem 冲突 author="
                        + post.getAuthorId() + " clientPostId=" + post.getClientPostId());
            }
        }
        rows.put(post.getId(), post);
    }

    @Override
    public Optional<Post> findById(long postId) {
        return Optional.ofNullable(rows.get(postId));
    }

    @Override
    public Optional<Post> findByClientPostId(long authorId, String clientPostId) {
        if (clientPostId == null || clientPostId.isBlank()) {
            return Optional.empty();
        }
        return rows.values().stream()
                .filter(row -> row.getAuthorId() == authorId
                        && clientPostId.equals(row.getClientPostId()))
                .findFirst();
    }

    @Override
    public List<Post> findByIds(Collection<Long> postIds) {
        List<Post> out = new ArrayList<>();
        for (Long id : postIds) {
            Post row = rows.get(id);
            if (row != null) {
                out.add(row);
            }
        }
        return out;
    }

    @Override
    public int countByAuthorSince(long authorId, LocalDateTime since) {
        return (int) rows.values().stream()
                .filter(row -> row.getAuthorId() == authorId)
                .filter(row -> row.getCreatedAt() != null && !row.getCreatedAt().isBefore(since))
                .count();
    }

    @Override
    public boolean deleteById(long postId) {
        return rows.remove(postId) != null;
    }

    @Override
    public void addLikeCount(long postId, int delta) {
        Post row = rows.get(postId);
        if (row != null) {
            row.setLikeCount(count(row.getLikeCount()) + delta);
        }
    }

    @Override
    public void addCommentCount(long postId, int delta) {
        Post row = rows.get(postId);
        if (row != null) {
            row.setCommentCount(count(row.getCommentCount()) + delta);
        }
    }

    @Override
    public List<Post> pageByAuthor(long authorId, Cursor cursor, int limit) {
        List<Post> out = new ArrayList<>(rows.values().stream()
                .filter(row -> row.getAuthorId() == authorId)
                .filter(row -> olderThan(row, cursor))
                .sorted(InMemoryPosts::newestFirst)
                .limit(Math.max(1, limit))
                .toList());
        return out;
    }

    @Override
    public List<Post> pagePublicExcluding(Collection<Long> excludeAuthors, Cursor cursor, int limit) {
        return new ArrayList<>(rows.values().stream()
                .filter(row -> row.getVisibility() == Visibility.PUBLIC)
                .filter(row -> excludeAuthors == null || !excludeAuthors.contains(row.getAuthorId()))
                .filter(row -> olderThan(row, cursor))
                .sorted(InMemoryPosts::newestFirst)
                .limit(Math.max(1, limit))
                .toList());
    }

    // ------------------------------------------------------------------ 测试用

    /** 直接塞一行（构造「别人已经发过动态」这类前置状态）。 */
    public void seed(Post post) {
        rows.put(post.getId(), post);
    }

    public int size() {
        return rows.size();
    }

    public Post get(long postId) {
        return rows.get(postId);
    }

    private static int newestFirst(Post x, Post y) {
        int byTime = y.getCreatedAt().compareTo(x.getCreatedAt());
        return byTime != 0 ? byTime : Long.compare(y.getId(), x.getId());
    }

    private static boolean olderThan(Post row, Cursor cursor) {
        if (cursor == null) {
            return true;
        }
        int byTime = row.getCreatedAt().compareTo(cursor.createdAt());
        return byTime < 0 || (byTime == 0 && row.getId() < cursor.postId());
    }

    private static int count(Integer value) {
        return value == null ? 0 : value;
    }
}
