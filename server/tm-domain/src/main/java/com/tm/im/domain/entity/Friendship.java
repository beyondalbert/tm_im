// 源: deploy/sql/01-schema.sql  sha256[:16]=7baf623506344ffd
package com.tm.im.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tm.im.domain.enums.FriendshipStatus;

import java.time.LocalDateTime;

/**
 * 好友关系，无序对存储
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

    /** 约定 actor_a < actor_b */
    // 联合主键之一，见类注释：故意不标 @TableId
    private Long actorA;

    // 联合主键之一，见类注释：故意不标 @TableId
    private Long actorB;

    /** 1=PENDING 2=ACCEPTED 3=BLOCKED */
    private FriendshipStatus status;

    /** 发起方，用于展示「谁加的你」 */
    private Long initiator;

    private LocalDateTime updatedAt;

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
                "actorA=" + actorA + ", " +
                "actorB=" + actorB + ", " +
                "status=" + status + ", " +
                "initiator=" + initiator + ", " +
                "updatedAt=" + updatedAt
                + '}';
    }
}
