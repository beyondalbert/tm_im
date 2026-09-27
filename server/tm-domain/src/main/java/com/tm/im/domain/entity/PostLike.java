// 源: deploy/sql/01-schema.sql  sha256[:16]=7818dd686897f64d
package com.tm.im.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 动态点赞
 *
 * <p>由 <code>tools/gen_entities.py</code> 从 <code>deploy/sql/01-schema.sql</code> 生成，
 * 请勿手工编辑——改结构请改 DDL 生成器后重跑。
 *
 * <p><b>本表是联合主键（post_id, actor_id），故刻意不标注 {@code @TableId}。</b>
 * MyBatis-Plus 不支持联合主键；若把其中一列强标为 {@code @TableId}，
 * 它生成的 {@code selectById} 会退化成 {@code WHERE post_id = ?}，
 * 在分片表上返回多行中的任意一行且不报错。这类静默错误比一条启动告警危险得多，
 * 因此选择让它告警。本表必须用显式条件查询。
 */
@TableName("post_like")
public class PostLike {

    // 联合主键之一，见类注释：故意不标 @TableId
    private Long postId;

    // 联合主键之一，见类注释：故意不标 @TableId
    private Long actorId;

    private LocalDateTime createdAt;

    public Long getPostId() {
        return postId;
    }

    public void setPostId(Long postId) {
        this.postId = postId;
    }

    public Long getActorId() {
        return actorId;
    }

    public void setActorId(Long actorId) {
        this.actorId = actorId;
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
        return "PostLike{" +
                "postId=" + postId + ", " +
                "actorId=" + actorId + ", " +
                "createdAt=" + createdAt
                + '}';
    }
}
