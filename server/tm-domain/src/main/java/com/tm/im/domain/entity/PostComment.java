// 源: deploy/sql/01-schema.sql  sha256[:16]=39b5c8e0aae10e1c
package com.tm.im.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 动态评论
 *
 * <p>由 <code>tools/gen_entities.py</code> 从 <code>deploy/sql/01-schema.sql</code> 生成，
 * 请勿手工编辑——改结构请改 DDL 生成器后重跑。
 */
@TableName("post_comment")
public class PostComment {

    /** Snowflake */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long postId;

    private Long authorId;

    private String content;

    /** 被回复的评论 id；顶层评论为 NULL */
    private Long replyToCommentId;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPostId() {
        return postId;
    }

    public void setPostId(Long postId) {
        this.postId = postId;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public void setAuthorId(Long authorId) {
        this.authorId = authorId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getReplyToCommentId() {
        return replyToCommentId;
    }

    public void setReplyToCommentId(Long replyToCommentId) {
        this.replyToCommentId = replyToCommentId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * 凭据类字段（secret / hash / token / password）一律脱敏。
     * 实体被随手打进日志是极常见的事，脱敏只有放在这里才拦得住。
     */
    @Override
    public String toString() {
        return "PostComment{" +
                "id=" + id + ", " +
                "postId=" + postId + ", " +
                "authorId=" + authorId + ", " +
                "content=" + content + ", " +
                "replyToCommentId=" + replyToCommentId + ", " +
                "createdAt=" + createdAt
                + '}';
    }
}
