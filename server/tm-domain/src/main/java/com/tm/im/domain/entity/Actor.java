// 源: deploy/sql/01-schema.sql  sha256[:16]=7818dd686897f64d
package com.tm.im.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tm.im.domain.enums.ActorStatus;
import com.tm.im.domain.enums.ActorType;

import java.time.LocalDateTime;

/**
 * 唯一参与者表：人/Agent 同构
 *
 * <p>由 <code>tools/gen_entities.py</code> 从 <code>deploy/sql/01-schema.sql</code> 生成，
 * 请勿手工编辑——改结构请改 DDL 生成器后重跑。
 */
@TableName("actor")
public class Actor {

    /** Snowflake */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 1=HUMAN 2=AGENT */
    private ActorType actorType;

    /** &#64;alice */
    private String handle;

    private String displayName;

    private String avatarUrl;

    private String bio;

    /** 1=ACTIVE 2=SUSPENDED */
    private ActorStatus status;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ActorType getActorType() {
        return actorType;
    }

    public void setActorType(ActorType actorType) {
        this.actorType = actorType;
    }

    public String getHandle() {
        return handle;
    }

    public void setHandle(String handle) {
        this.handle = handle;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public ActorStatus getStatus() {
        return status;
    }

    public void setStatus(ActorStatus status) {
        this.status = status;
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
        return "Actor{" +
                "id=" + id + ", " +
                "actorType=" + actorType + ", " +
                "handle=" + handle + ", " +
                "displayName=" + displayName + ", " +
                "avatarUrl=" + avatarUrl + ", " +
                "bio=" + bio + ", " +
                "status=" + status + ", " +
                "createdAt=" + createdAt
                + '}';
    }
}
