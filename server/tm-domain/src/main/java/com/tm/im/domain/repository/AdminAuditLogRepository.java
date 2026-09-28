package com.tm.im.domain.repository;

import com.tm.im.domain.entity.AdminAuditLog;

import java.util.List;

/**
 * 后台操作审计仓储。
 *
 * <p><b>只追加，不修改、不删除</b>：审计表一旦可以被改，它证明的东西就只剩
 * 「当时大概是这么回事」。所以本接口只有 {@code insert} 与查询，
 * 连「清理旧记录」都不在这里——那属于运维动作，应该走 DBA 脚本并留下痕迹。
 *
 * <p><b>写入必须在与业务变更同一个事务里</b>（由服务层保证）：
 * 先改后记、或先记后改都会留下不一致——前者是「封了号但查不到谁封的」，
 * 后者是「日志说封了但实际没封」，而后者更危险：它会让人相信一个不存在的动作。
 */
public interface AdminAuditLogRepository {

    void insert(AdminAuditLog row);

    /**
     * 分页查询，按 {@code id} 倒序（{@code id} 是 Snowflake，
     * 与 {@code created_at} 同序，见 {@code AdminUserRepository} 的类注释）。
     *
     * <p>四个过滤条件都可为 null，含义是「不过滤」。它们分别对应三种真实问题：
     * 「某个管理员干过什么」（{@code adminId}）、
     * 「这个对象被谁动过」（{@code targetType}+{@code targetId}）、
     * 「这类操作发生过几次」（{@code action}）。
     */
    List<AdminAuditLog> page(Long beforeId, int limit, Long adminId, String action,
                             String targetType, Long targetId);
}
