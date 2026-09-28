package com.tm.im.storage.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tm.im.domain.entity.AdminUser;
import com.tm.im.domain.enums.AdminStatus;
import com.tm.im.domain.repository.AdminUserRepository;
import com.tm.im.storage.mapper.AdminUserMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 后台账号仓储实现。
 *
 * <p>{@code admin_user} 是非分片表，所以这里全是主键/唯一键点查与一次索引分页，
 * 没有「不带分片键就广播 16 张表」的问题（DESIGN §8.7），也不需要事务。
 *
 * <p>两次「按 id 倒序」的分页都用显式 {@code orderByDesc}：不写排序时顺序由存储引擎
 * 决定（恰好是主键序），换索引或换版本就会变，而客户端会把它当成「列表顺序变了」。
 */
@Repository
public class AdminUserRepositoryImpl implements AdminUserRepository {

    private final AdminUserMapper mapper;

    public AdminUserRepositoryImpl(AdminUserMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<AdminUser> findById(long adminId) {
        return Optional.ofNullable(mapper.selectById(adminId));
    }

    @Override
    public Optional<AdminUser> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            // 空用户名不该变成一次「WHERE username=''」的查询：那条语句永远查不到东西，
            // 但它会把「调用方传错了」伪装成「账号不存在」——而登录路径上这两者的
            // 处理完全不同（前者是 bug，后者是正常的失败）。
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(Wrappers.<AdminUser>lambdaQuery()
                .eq(AdminUser::getUsername, username)));
    }

    @Override
    public long count() {
        Long n = mapper.selectCount(Wrappers.<AdminUser>lambdaQuery());
        return n == null ? 0L : n;
    }

    @Override
    public void insert(AdminUser admin) {
        mapper.insert(admin);
    }

    @Override
    public void update(AdminUser admin) {
        // 只写这三个字段：username 不可变，口令与登录状态各有专门的方法（见接口注释）。
        mapper.update(null, Wrappers.<AdminUser>lambdaUpdate()
                .eq(AdminUser::getId, admin.getId())
                .set(AdminUser::getDisplayName, admin.getDisplayName())
                .set(AdminUser::getRole, admin.getRole())
                .set(AdminUser::getStatus, admin.getStatus()));
    }

    @Override
    public void updatePasswordHash(long adminId, String passwordHash) {
        mapper.update(null, Wrappers.<AdminUser>lambdaUpdate()
                .eq(AdminUser::getId, adminId)
                .set(AdminUser::getPasswordHash, passwordHash));
    }

    @Override
    public void updateLoginState(long adminId, int failedAttempts, LocalDateTime lockedUntil,
                                 LocalDateTime lastLoginAt) {
        // 三个字段一条 UPDATE：分开写会留下「计数加了但锁定时间没写」的中间态，
        // 而在防爆破这条路径上，中间态就是可以绕过的意思（见接口注释）。
        mapper.update(null, Wrappers.<AdminUser>lambdaUpdate()
                .eq(AdminUser::getId, adminId)
                .set(AdminUser::getFailedAttempts, failedAttempts)
                .set(AdminUser::getLockedUntil, lockedUntil)
                .set(AdminUser::getLastLoginAt, lastLoginAt));
    }

    @Override
    public List<AdminUser> page(Long beforeId, int limit) {
        return mapper.selectList(Wrappers.<AdminUser>lambdaQuery()
                .lt(beforeId != null, AdminUser::getId, beforeId)
                .orderByDesc(AdminUser::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    @Override
    public void updateStatus(long adminId, AdminStatus status) {
        mapper.update(null, Wrappers.<AdminUser>lambdaUpdate()
                .eq(AdminUser::getId, adminId)
                .set(AdminUser::getStatus, status));
    }
}
