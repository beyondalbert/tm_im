package com.tm.im.storage.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tm.im.domain.entity.AdminAuditLog;
import com.tm.im.domain.repository.AdminAuditLogRepository;
import com.tm.im.storage.mapper.AdminAuditLogMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 后台审计仓储实现。
 *
 * <p>查询侧的三条索引与三个查询形态一一对应（DDL 里写明了）：
 * {@code idx_admin_time}（某个管理员干过什么）、
 * {@code idx_target_time}（这个对象被谁动过）、
 * {@code idx_time}（最近都发生了什么）。
 *
 * <p>四个条件都用 {@code {condition}} 形式（{@code eq(false, ...)} 不拼这一段）：
 * 这样「无条件」与「条件为 null」是同一段代码，不会出现「只过滤作者时忘了清掉状态条件」
 * 这种拼装错误，也不会在 SQL 里留下 {@code AND action = NULL}。
 */
@Repository
public class AdminAuditLogRepositoryImpl implements AdminAuditLogRepository {

    private final AdminAuditLogMapper mapper;

    public AdminAuditLogRepositoryImpl(AdminAuditLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(AdminAuditLog row) {
        mapper.insert(row);
    }

    @Override
    public List<AdminAuditLog> page(Long beforeId, int limit, Long adminId, String action,
                                    String targetType, Long targetId) {
        return mapper.selectList(Wrappers.<AdminAuditLog>lambdaQuery()
                .lt(beforeId != null, AdminAuditLog::getId, beforeId)
                .eq(adminId != null, AdminAuditLog::getAdminId, adminId)
                .eq(action != null && !action.isBlank(), AdminAuditLog::getAction, action)
                .eq(targetType != null && !targetType.isBlank(),
                        AdminAuditLog::getTargetType, targetType)
                .eq(targetId != null, AdminAuditLog::getTargetId, targetId)
                .orderByDesc(AdminAuditLog::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }
}
