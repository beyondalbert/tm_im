// 源: deploy/sql/01-schema.sql  sha256[:16]=7baf623506344ffd
package com.tm.im.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tm.im.domain.enums.MemberRole;

import java.time.LocalDateTime;

/**
 * 会话成员
 *
 * <p>由 <code>tools/gen_entities.py</code> 从 <code>deploy/sql/01-schema.sql</code> 生成，
 * 请勿手工编辑——改结构请改 DDL 生成器后重跑。
 *
 * <p><b>本表是联合主键（conv_id, actor_id），故刻意不标注 {@code @TableId}。</b>
 * MyBatis-Plus 不支持联合主键；若把其中一列强标为 {@code @TableId}，
 * 它生成的 {@code selectById} 会退化成 {@code WHERE conv_id = ?}，
 * 在分片表上返回多行中的任意一行且不报错。这类静默错误比一条启动告警危险得多，
 * 因此选择让它告警。本表必须用显式条件查询。
 */
@TableName("conversation_member")
public class ConversationMember {

    // 联合主键之一，见类注释：故意不标 @TableId
    private Long convId;

    // 联合主键之一，见类注释：故意不标 @TableId
    private Long actorId;

    /** 1=OWNER 2=ADMIN 3=MEMBER */
    private MemberRole role;

    /** 统一未读游标 */
    private Long lastReadSeq;

    private Boolean muted;

    private LocalDateTime joinedAt;

    public Long getConvId() {
        return convId;
    }

    public void setConvId(Long convId) {
        this.convId = convId;
    }

    public Long getActorId() {
        return actorId;
    }

    public void setActorId(Long actorId) {
        this.actorId = actorId;
    }

    public MemberRole getRole() {
        return role;
    }

    public void setRole(MemberRole role) {
        this.role = role;
    }

    public Long getLastReadSeq() {
        return lastReadSeq;
    }

    public void setLastReadSeq(Long lastReadSeq) {
        this.lastReadSeq = lastReadSeq;
    }

    public Boolean getMuted() {
        return muted;
    }

    public void setMuted(Boolean muted) {
        this.muted = muted;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(LocalDateTime joinedAt) {
        this.joinedAt = joinedAt;
    }

    /**
     * 凭据类字段（secret / hash / token / password）一律脱敏。
     * 实体被随手打进日志是极常见的事，脱敏只有放在这里才拦得住。
     */
    @Override
    public String toString() {
        return "ConversationMember{" +
                "convId=" + convId + ", " +
                "actorId=" + actorId + ", " +
                "role=" + role + ", " +
                "lastReadSeq=" + lastReadSeq + ", " +
                "muted=" + muted + ", " +
                "joinedAt=" + joinedAt
                + '}';
    }
}
