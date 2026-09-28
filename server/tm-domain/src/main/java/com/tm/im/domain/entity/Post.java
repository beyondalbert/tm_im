// 源: deploy/sql/01-schema.sql  sha256[:16]=39b5c8e0aae10e1c
package com.tm.im.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tm.im.domain.enums.Visibility;

import java.time.LocalDateTime;

/**
 * 动态
 *
 * <p>由 <code>tools/gen_entities.py</code> 从 <code>deploy/sql/01-schema.sql</code> 生成，
 * 请勿手工编辑——改结构请改 DDL 生成器后重跑。
 */
@TableName("post")
public class Post {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long authorId;

    private String content;

    /** 1=PUBLIC 2=FRIENDS_ONLY */
    private Visibility visibility;

    /** 冗余计数，与 post_like 同事务更新 */
    private Integer likeCount;

    /** 冗余计数，与 post_comment 同事务更新 */
    private Integer commentCount;

    /** 幂等键（客户端生成，可空） */
    private String clientPostId;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public Visibility getVisibility() {
        return visibility;
    }

    public void setVisibility(Visibility visibility) {
        this.visibility = visibility;
    }

    public Integer getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(Integer likeCount) {
        this.likeCount = likeCount;
    }

    public Integer getCommentCount() {
        return commentCount;
    }

    public void setCommentCount(Integer commentCount) {
        this.commentCount = commentCount;
    }

    public String getClientPostId() {
        return clientPostId;
    }

    public void setClientPostId(String clientPostId) {
        this.clientPostId = clientPostId;
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
        return "Post{" +
                "id=" + id + ", " +
                "authorId=" + authorId + ", " +
                "content=" + content + ", " +
                "visibility=" + visibility + ", " +
                "likeCount=" + likeCount + ", " +
                "commentCount=" + commentCount + ", " +
                "clientPostId=" + clientPostId + ", " +
                "createdAt=" + createdAt
                + '}';
    }
}
