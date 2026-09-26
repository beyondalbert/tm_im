// 源: deploy/sql/01-schema.sql  sha256[:16]=6e25d69f6d539a88
package com.tm.im.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tm.im.domain.enums.ConvType;

import java.time.LocalDateTime;

/**
 * 会话；单聊/群聊同构
 *
 * <p>由 <code>tools/gen_entities.py</code> 从 <code>deploy/sql/01-schema.sql</code> 生成，
 * 请勿手工编辑——改结构请改 DDL 生成器后重跑。
 */
@TableName("conversation")
public class Conversation {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 1=DIRECT 2=GROUP */
    private ConvType convType;

    private String title;

    /** 群主；单聊为 NULL */
    private Long ownerActor;

    /** Redis 不可用时的兜底序号 */
    private Long seqCounter;

    /** 单聊去重键：min_max */
    private String pairKey;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ConvType getConvType() {
        return convType;
    }

    public void setConvType(ConvType convType) {
        this.convType = convType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Long getOwnerActor() {
        return ownerActor;
    }

    public void setOwnerActor(Long ownerActor) {
        this.ownerActor = ownerActor;
    }

    public Long getSeqCounter() {
        return seqCounter;
    }

    public void setSeqCounter(Long seqCounter) {
        this.seqCounter = seqCounter;
    }

    public String getPairKey() {
        return pairKey;
    }

    public void setPairKey(String pairKey) {
        this.pairKey = pairKey;
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
        return "Conversation{" +
                "id=" + id + ", " +
                "convType=" + convType + ", " +
                "title=" + title + ", " +
                "ownerActor=" + ownerActor + ", " +
                "seqCounter=" + seqCounter + ", " +
                "pairKey=" + pairKey + ", " +
                "createdAt=" + createdAt
                + '}';
    }
}
