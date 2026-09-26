// 源: deploy/sql/01-schema.sql  sha256[:16]=3b1df4f3ef040428
package com.tm.im.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tm.im.domain.enums.FriendshipStatus;

import java.time.LocalDateTime;

/**
 * 好友关系（含请求生命周期），无序对存储
 *
 * <p>由 <code>tools/gen_entities.py</code> 从 <code>deploy/sql/01-schema.sql</code> 生成，
 * 请勿手工编辑——改结构请改 DDL 生成器后重跑。
 *
 * <p><b>本表是联合主键（actor_a, actor_b），故刻意不标注 {@code @TableId}。</b>
 * MyBatis-Plus 不支持联合主键；若把其中一列强标为 {@code @TableId}，
 * 它生成的 {@code selectById} 会退化成 {@code WHERE actor_a = ?}，
 * 在分片表上返回多行中的任意一行且不报错。这类静默错误比一条启动告警危险得多，
 * 因此选择让它告警。本表必须用显式条件查询。
 */
@TableName("friendship")
public class Friendship {

    /** 好友请求 id（雪花号）；accept/reject 按它定位 */
    private Long requestId;

    /** 约定 actor_a < actor_b */
    // 联合主键之一，见类注释：故意不标 @TableId
    private Long actorA;

    // 联合主键之一，见类注释：故意不标 @TableId
    private Long actorB;

    /** 1=PENDING 2=ACCEPTED 3=BLOCKED */
    private FriendshipStatus status;

    /** 发起方，用于展示「谁加的你」 */
    private Long initiator;

    /** 请求附言（仅 PENDING 时有意义） */
    private String message;

    /** PENDING 的失效时间（ACCEPTED/BLOCKED 后保留原值，不再有意义） */
    private LocalDateTime expiresAt;

    /** 关系（或请求）建立时间 */
    private LocalDateTime createdAt;

    /** 最后一次状态变更时间；ACCEPTED 行的它就是 friends_since */
    private LocalDateTime updatedAt;

    public Long getRequestId() {
        return requestId;
    }

    public void setRequestId(Long requestId) {
        this.requestId = requestId;
    }

    public Long getActorA() {
        return actorA;
    }

    public void setActorA(Long actorA) {
        this.actorA = actorA;
    }

    public Long getActorB() {
        return actorB;
    }

    public void setActorB(Long actorB) {
        this.actorB = actorB;
    }

    public FriendshipStatus getStatus() {
        return status;
    }

    public void setStatus(FriendshipStatus status) {
        this.status = status;
    }

    public Long getInitiator() {
        return initiator;
    }

    public void setInitiator(Long initiator) {
        this.initiator = initiator;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * 凭据类字段（secret / hash / token / password）一律脱敏。
     * 实体被随手打进日志是极常见的事，脱敏只有放在这里才拦得住。
     */
    @Override
    public String toString() {
        return "Friendship{" +
                "requestId=" + requestId + ", " +
                "actorA=" + actorA + ", " +
                "actorB=" + actorB + ", " +
                "status=" + status + ", " +
                "initiator=" + initiator + ", " +
                "message=" + message + ", " +
                "expiresAt=" + expiresAt + ", " +
                "createdAt=" + createdAt + ", " +
                "updatedAt=" + updatedAt
                + '}';
    }
}
