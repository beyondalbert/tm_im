package com.tm.im.storage.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tm.im.domain.entity.PostLike;
import com.tm.im.domain.repository.PostLikeRepository;
import com.tm.im.storage.mapper.PostLikeMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 点赞仓储实现。
 *
 * <p><b>幂等由主键承担，不由「先查再写」承担</b>：主键 {@code (post_id, actor_id)}
 * 让并发双击里的第二个请求必然撞唯一键，于是「谁该加计数」这个判断只有一个正确答案
 * （返回 true 的那个）。先查再写则会在两个请求都查不到时各插一行——
 * 而 {@code like_count} 会被加两次，且库里明明只有一行点赞记录。
 */
@Repository
public class PostLikeRepositoryImpl implements PostLikeRepository {

    private final PostLikeMapper mapper;

    public PostLikeRepositoryImpl(PostLikeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public boolean like(long postId, long actorId, LocalDateTime now) {
        PostLike row = new PostLike();
        row.setPostId(postId);
        row.setActorId(actorId);
        row.setCreatedAt(now);
        try {
            mapper.insert(row);
            return true;
        } catch (DuplicateKeyException e) {
            // 已经赞过（40907 的来源）。不回滚计数：计数根本没被改过。
            return false;
        }
    }

    @Override
    @Transactional
    public boolean unlike(long postId, long actorId) {
        return mapper.delete(Wrappers.<PostLike>lambdaQuery()
                .eq(PostLike::getPostId, postId)
                .eq(PostLike::getActorId, actorId)) > 0;
    }

    @Override
    public Set<Long> findLikedPostIds(long actorId, Collection<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(mapper.selectList(Wrappers.<PostLike>lambdaQuery()
                        .eq(PostLike::getActorId, actorId)
                        .in(PostLike::getPostId, postIds))
                .stream().map(PostLike::getPostId).toList());
    }

    @Override
    public int deleteByPostId(long postId) {
        return mapper.delete(Wrappers.<PostLike>lambdaQuery().eq(PostLike::getPostId, postId));
    }
}
