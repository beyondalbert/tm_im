// 源: deploy/sql/01-schema.sql  sha256[:16]=39b5c8e0aae10e1c
package com.tm.im.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tm.im.domain.enums.AdminRole;
import com.tm.im.domain.enums.AdminStatus;

import java.time.LocalDateTime;

/**
 * 后台账号（独立于 actor 的身份体系）
 *
 * <p>由 <code>tools/gen_entities.py</code> 从 <code>deploy/sql/01-schema.sql</code> 生成，
 * 请勿手工编辑——改结构请改 DDL 生成器后重跑。
 */
@TableName("admin_user")
public class AdminUser {

    /** Snowflake */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String username;

    private String displayName;

    /** pbkdf2-sha256$迭代数$盐$摘要 */
    private String passwordHash;

    /** 1=SUPER 2=OPS */
    private AdminRole role;

    /** 1=ACTIVE 2=DISABLED */
    private AdminStatus status;

    /** 连续登录失败次数，成功即清零 */
    private Integer failedAttempts;

    /** 锁定到什么时候（防在线爆破） */
    private LocalDateTime lockedUntil;

    private LocalDateTime createdAt;

    private LocalDateTime lastLoginAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public AdminRole getRole() {
        return role;
    }

    public void setRole(AdminRole role) {
        this.role = role;
    }

    public AdminStatus getStatus() {
        return status;
    }

    public void setStatus(AdminStatus status) {
        this.status = status;
    }

    public Integer getFailedAttempts() {
        return failedAttempts;
    }

    public void setFailedAttempts(Integer failedAttempts) {
        this.failedAttempts = failedAttempts;
    }

    public LocalDateTime getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(LocalDateTime lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    /**
     * 凭据类字段（secret / hash / token / password）一律脱敏。
     * 实体被随手打进日志是极常见的事，脱敏只有放在这里才拦得住。
     */
    @Override
    public String toString() {
        return "AdminUser{" +
                "id=" + id + ", " +
                "username=" + username + ", " +
                "displayName=" + displayName + ", " +
                "passwordHash=***" + ", " +
                "role=" + role + ", " +
                "status=" + status + ", " +
                "failedAttempts=" + failedAttempts + ", " +
                "lockedUntil=" + lockedUntil + ", " +
                "createdAt=" + createdAt + ", " +
                "lastLoginAt=" + lastLoginAt
                + '}';
    }
}
