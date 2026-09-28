package com.tm.im.storage.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tm.im.domain.entity.AdminSession;
import com.tm.im.domain.repository.AdminSessionRepository;
import com.tm.im.storage.mapper.AdminSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 后台会话仓储实现。
 *
 * <p><b>{@link #touch} 刻意吞掉异常</b>：它只更新 {@code last_seen_at}，
 * 而这个字段的用途是「运维看一眼这个会话最近还在不在用」。
 * 让它抛异常等于把一个纯观测字段变成鉴权的失败点——一次 UPDATE 死锁
 * 会让一个合法管理员被踢出去，而他看到的提示会是 401。
 */
@Repository
public class AdminSessionRepositoryImpl implements AdminSessionRepository {

    private static final Logger log = LoggerFactory.getLogger(AdminSessionRepositoryImpl.class);

    private final AdminSessionMapper mapper;

    public AdminSessionRepositoryImpl(AdminSessionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(AdminSession session) {
        mapper.insert(session);
    }

    @Override
    public Optional<AdminSession> findByTokenHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(Wrappers.<AdminSession>lambdaQuery()
                .eq(AdminSession::getTokenHash, tokenHash)));
    }

    @Override
    public void touch(long sessionId, LocalDateTime at) {
        try {
            mapper.update(null, Wrappers.<AdminSession>lambdaUpdate()
                    .eq(AdminSession::getId, sessionId)
                    .set(AdminSession::getLastSeenAt, at));
        } catch (RuntimeException e) {
            log.warn("更新会话 last_seen_at 失败 sessionId={}：不影响鉴权结果", sessionId, e);
        }
    }

    @Override
    public boolean deleteByTokenHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return false;
        }
        return mapper.delete(Wrappers.<AdminSession>lambdaQuery()
                .eq(AdminSession::getTokenHash, tokenHash)) > 0;
    }

    @Override
    public int deleteByAdminId(long adminId) {
        return mapper.delete(Wrappers.<AdminSession>lambdaQuery()
                .eq(AdminSession::getAdminId, adminId));
    }

    @Override
    public int deleteExpired(LocalDateTime before) {
        return mapper.delete(Wrappers.<AdminSession>lambdaQuery()
                .lt(AdminSession::getExpiresAt, before));
    }
}
