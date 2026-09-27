package com.tm.im.storage.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tm.im.domain.entity.PostComment;
import com.tm.im.domain.repository.PostCommentRepository;
import com.tm.im.storage.mapper.PostCommentMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 评论仓储实现。
 *
 * <p>列表走 {@code idx_post_time (post_id, created_at)}：等值 + 升序范围，
 * 于是一条讨论线是一次顺序扫描。删除按主键点查（{@code id} 是雪花号，
 * 没有 {@code post_id} 也能删——这一点与「删除评论」的权限判定有关：
 * 权限取决于评论行本身，而调用方往往先按下发过的 comment_id 定位它）。
 */
@Repository
public class PostCommentRepositoryImpl implements PostCommentRepository {

    private final PostCommentMapper mapper;

    public PostCommentRepositoryImpl(PostCommentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(PostComment comment) {
        mapper.insert(comment);
    }

    @Override
    public Optional<PostComment> findById(long commentId) {
        if (commentId <= 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(commentId));
    }

    @Override
    public List<PostComment> pageByPost(long postId, Cursor cursor, int limit) {
        return mapper.selectList(Wrappers.<PostComment>lambdaQuery()
                .eq(PostComment::getPostId, postId)
                .apply(cursorWhere(cursor))
                .orderByAsc(PostComment::getCreatedAt)
                .orderByAsc(PostComment::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    @Override
    public boolean deleteById(long commentId) {
        return mapper.deleteById(commentId) > 0;
    }

    @Override
    public int deleteByPostId(long postId) {
        return mapper.delete(Wrappers.<PostComment>lambdaQuery().eq(PostComment::getPostId, postId));
    }

    /**
     * 评论的游标谓词是「比这一条<b>更新</b>」（{@code >}），与信息流的「更旧」相反：
     * 一条讨论线从上往下读，第二页自然是第一页之后的那些。
     */
    private static String cursorWhere(Cursor cursor) {
        if (cursor == null) {
            return "1 = 1";
        }
        String at = cursor.createdAt().toString();
        return "(created_at > '" + at + "' OR (created_at = '" + at
                + "' AND id > " + cursor.commentId() + "))";
    }
}
