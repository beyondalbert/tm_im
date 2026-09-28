package com.tm.im.storage.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tm.im.domain.entity.Post;
import com.tm.im.domain.enums.Visibility;
import com.tm.im.domain.repository.PostRepository;
import com.tm.im.storage.mapper.PostMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 动态仓储实现。
 *
 * <p>四条查询路径，各自对应的索引写在各方法上。它们都<b>没有</b>
 * 「先全表按时间扫一遍再过滤」的形态——广场是公开数据，一旦退化成全表扫描，
 * 它的代价会随全站动态量线性增长，而不是随单个用户的可见范围。
 */
@Repository
public class PostRepositoryImpl implements PostRepository {

    private final PostMapper mapper;

    public PostRepositoryImpl(PostMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(Post post) {
        mapper.insert(post);
    }

    @Override
    public Optional<Post> findById(long postId) {
        if (postId <= 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(postId));
    }

    @Override
    public Optional<Post> findByClientPostId(long authorId, String clientPostId) {
        if (clientPostId == null || clientPostId.isBlank()) {
            // 没带幂等键的调用方永远查不到「既有动态」：NULL 在唯一索引里不参与去重，
            // 因此「不带键」就只能每次新建（与 07-errors-limits.md §3.5 的现状一致）。
            return Optional.empty();
        }
        // uk_post_idem (author_id, client_post_id)：两个列都在索引里，且顺序与谓词一致
        return Optional.ofNullable(mapper.selectOne(Wrappers.<Post>lambdaQuery()
                .eq(Post::getAuthorId, authorId)
                .eq(Post::getClientPostId, clientPostId)
                .last("LIMIT 1")));
    }

    @Override
    public List<Post> findByIds(Collection<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return List.of();
        }
        return mapper.selectByIds(postIds);
    }

    @Override
    public int countByAuthorSince(long authorId, LocalDateTime since) {
        // idx_author_time (author_id, created_at) 上的范围计数
        return Math.toIntExact(mapper.selectCount(Wrappers.<Post>lambdaQuery()
                .eq(Post::getAuthorId, authorId)
                .ge(Post::getCreatedAt, since)));
    }

    @Override
    public boolean deleteById(long postId) {
        return mapper.deleteById(postId) > 0;
    }

    @Override
    public void addLikeCount(long postId, int delta) {
        mapper.update(null, Wrappers.<Post>lambdaUpdate()
                .eq(Post::getId, postId)
                .setSql("like_count = like_count + " + delta));
    }

    @Override
    public void addCommentCount(long postId, int delta) {
        mapper.update(null, Wrappers.<Post>lambdaUpdate()
                .eq(Post::getId, postId)
                .setSql("comment_count = comment_count + " + delta));
    }

    @Override
    public List<Post> pageByAuthor(long authorId, Cursor cursor, int limit) {
        // idx_author_time (author_id, created_at)：等值 + 范围，顺序扫描到 LIMIT 即止
        return mapper.selectList(Wrappers.<Post>lambdaQuery()
                .eq(Post::getAuthorId, authorId)
                .apply(cursorWhere(cursor))
                .orderByDesc(Post::getCreatedAt)
                .orderByDesc(Post::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    @Override
    public List<Post> pagePublicExcluding(Collection<Long> excludeAuthors, Cursor cursor, int limit) {
        LambdaQueryWrapper<Post> wrapper = Wrappers.<Post>lambdaQuery()
                // idx_visibility_time (visibility, created_at)：可见性是等值前缀，
                // 时间走范围，于是「公开流」是一次范围扫描而不是按作者逐个查
                .eq(Post::getVisibility, Visibility.PUBLIC)
                .apply(cursorWhere(cursor));
        if (excludeAuthors != null && !excludeAuthors.isEmpty()) {
            // NOT IN 的列表长度由服务层收敛（见 PlazaService 的 FRIEND_SET_CAP）：
            // 这里不做截断，避免「悄悄少排除几个人」变成信息流里的重复动态。
            wrapper.notIn(Post::getAuthorId, excludeAuthors);
        }
        return mapper.selectList(wrapper
                .orderByDesc(Post::getCreatedAt)
                .orderByDesc(Post::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    @Override
    public List<Post> pageForModeration(Long beforeId, int limit) {
        // ORDER BY id DESC 走主键（聚簇索引），不需要额外索引，也不会回表：
        // post 的读取本来就要把绝大多数列取出来（渲染动态要正文与图片）。
        return mapper.selectList(Wrappers.<Post>lambdaQuery()
                .lt(beforeId != null, Post::getId, beforeId)
                .orderByDesc(Post::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 游标谓词：严格「比游标更旧」，且 {@code (created_at, id)} 作为<b>一对</b>比较。
     *
     * <p>只写 {@code created_at < ?} 会漏掉「同一毫秒里的其余动态」——
     * 而 Agent 批量发帖时同毫秒多条是常态，表现为信息流每隔一段就少几条。
     * 括号必须真实存在（同 {@code FriendshipRepositoryImpl#cursorWhere} 的注释）。
     *
     * <p>拼进去的是 {@code LocalDateTime.toString()}（ISO 字面量，MySQL 的
     * {@code DATETIME(3)} 能直接解析）与 {@code long}，不是用户输入，没有注入面。
     */
    private static String cursorWhere(Cursor cursor) {
        if (cursor == null) {
            return "1 = 1";
        }
        String at = cursor.createdAt().toString();
        return "(created_at < '" + at + "' OR (created_at = '" + at
                + "' AND id < " + cursor.postId() + "))";
    }
}
