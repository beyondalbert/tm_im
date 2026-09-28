// 源: deploy/sql/01-schema.sql  sha256[:16]=39b5c8e0aae10e1c
package com.tm.im.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 后台操作审计（谁在什么时候改了什么）
 *
 * <p>由 <code>tools/gen_entities.py</code> 从 <code>deploy/sql/01-schema.sql</code> 生成，
 * 请勿手工编辑——改结构请改 DDL 生成器后重跑。
 */
@TableName("admin_audit_log")
public class AdminAuditLog {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long adminId;

    /** 冗余快照：账号改名/删除后仍可读 */
    private String adminName;

    /** ACTOR_SUSPEND / POST_DELETE / ADMIN_LOGIN … */
    private String action;

    /** ACTOR / POST / AGENT / ADMIN */
    private String targetType;

    private Long targetId;

    /** 结构化详情，便于以后加字段 */
    private String detail;

    private String ip;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAdminId() {
        return adminId;
    }

    public void setAdminId(Long adminId) {
        this.adminId = adminId;
    }

    public String getAdminName() {
        return adminName;
    }

    public void setAdminName(String adminName) {
        this.adminName = adminName;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
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
        return "AdminAuditLog{" +
                "id=" + id + ", " +
                "adminId=" + adminId + ", " +
                "adminName=" + adminName + ", " +
                "action=" + action + ", " +
                "targetType=" + targetType + ", " +
                "targetId=" + targetId + ", " +
                "detail=" + detail + ", " +
                "ip=" + ip + ", " +
                "createdAt=" + createdAt
                + '}';
    }
}
