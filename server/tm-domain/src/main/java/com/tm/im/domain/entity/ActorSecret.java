// 源: deploy/sql/01-schema.sql  sha256[:16]=6e25d69f6d539a88
package com.tm.im.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tm.im.domain.enums.SecretType;

import java.time.LocalDateTime;

/**
 * 凭据，与人/Agent 无关
 *
 * <p>由 <code>tools/gen_entities.py</code> 从 <code>deploy/sql/01-schema.sql</code> 生成，
 * 请勿手工编辑——改结构请改 DDL 生成器后重跑。
 *
 * <p><b>本表是联合主键（actor_id, secret_type），故刻意不标注 {@code @TableId}。</b>
 * MyBatis-Plus 不支持联合主键；若把其中一列强标为 {@code @TableId}，
 * 它生成的 {@code selectById} 会退化成 {@code WHERE actor_id = ?}，
 * 在分片表上返回多行中的任意一行且不报错。这类静默错误比一条启动告警危险得多，
 * 因此选择让它告警。本表必须用显式条件查询。
 */
@TableName("actor_secret")
public class ActorSecret {

    // 联合主键之一，见类注释：故意不标 @TableId
    private Long actorId;

    /** 1=密码哈希 2=API_KEY哈希 3=WEBHOOK密钥(明文,需用于签名) */
    // 联合主键之一，见类注释：故意不标 @TableId
    private SecretType secretType;

    private String secretHash;

    private LocalDateTime lastUsedAt;

    public Long getActorId() {
        return actorId;
    }

    public void setActorId(Long actorId) {
        this.actorId = actorId;
    }

    public SecretType getSecretType() {
        return secretType;
    }

    public void setSecretType(SecretType secretType) {
        this.secretType = secretType;
    }

    public String getSecretHash() {
        return secretHash;
    }

    public void setSecretHash(String secretHash) {
        this.secretHash = secretHash;
    }

    public LocalDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(LocalDateTime lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    /**
     * 凭据类字段（secret / hash / token / password）一律脱敏。
     * 实体被随手打进日志是极常见的事，脱敏只有放在这里才拦得住。
     */
    @Override
    public String toString() {
        return "ActorSecret{" +
                "actorId=" + actorId + ", " +
                "secretType=***" + ", " +
                "secretHash=***" + ", " +
                "lastUsedAt=" + lastUsedAt
                + '}';
    }
}
