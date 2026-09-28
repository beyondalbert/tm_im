package com.tm.im.core.admin;

import com.tm.im.common.crypto.OpaqueToken;
import com.tm.im.common.crypto.PasswordHashes;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.common.id.IdGenerator;
import com.tm.im.domain.entity.AdminAuditLog;
import com.tm.im.domain.entity.AdminSession;
import com.tm.im.domain.entity.AdminUser;
import com.tm.im.domain.enums.AdminRole;
import com.tm.im.domain.enums.AdminStatus;
import com.tm.im.domain.repository.AdminAuditLogRepository;
import com.tm.im.domain.repository.AdminSessionRepository;
import com.tm.im.domain.repository.AdminUserRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 后台三张表的内存替身（账号 / 会话 / 审计）。
 *
 * <p>手写而不是 Mockito，与本仓库其它替身同一取舍（见 {@code InMemoryActors} 的注释）。
 * 这里还多一条理由：本服务的用例几乎都要断言「审计里留下了什么」，
 * 而那个断言在 mock 上会写成「验证 insert 被调用了一次 + 参数匹配」——
 * 一旦审计行的<b>内容</b>（谁、对谁、detail 里有什么）成为断言对象，
 * 手写替身读起来就是一句话。
 */
public final class InMemoryAdminStore {

    /** 从 1000 起造夹具行；生成器给的是 500000（两段 id 区间不重叠，见 idGenerator 的注释）。 */
    private final AtomicLong ids = new AtomicLong(500000);

    public final Users users = new Users();
    public final Sessions sessions = new Sessions();
    public final Audits audits = new Audits();

    public long nextId() {
        return ids.incrementAndGet();
    }

    /**
     * 固定递增值的 IdGenerator。
     *
     * <p>起点刻意远离 1000（夹具用它造行）：两者共用一段 id 时，
     * 「新建一个账号」会与「夹具里那个账号」撞主键，而报错会指向替身的 insert
     * 而不是用例本身。（这一条是被测试自己拓出来的。）
     */
    public IdGenerator idGenerator() {
        return new IdGenerator() {
            @Override
            public long nextId() {
                return InMemoryAdminStore.this.nextId();
            }

            @Override
            public int nodeId() {
                return 7;
            }
        };
    }

    public static final class Users implements AdminUserRepository {

        private final Map<Long, AdminUser> byId = new LinkedHashMap<>();

        /** 造一个账号：口令用真实的 PBKDF2 哈希（迭代数调小，让单测跑得快）。 */
        public AdminUser put(String username, String password, AdminRole role, AdminStatus status) {
            AdminUser admin = new AdminUser();
            admin.setId(1000L + byId.size() + 1);
            admin.setUsername(username);
            admin.setDisplayName(username);
            admin.setPasswordHash(PasswordHashes.hash(password, 1000));
            admin.setRole(role);
            admin.setStatus(status);
            admin.setFailedAttempts(0);
            admin.setCreatedAt(LocalDateTime.of(2026, 1, 1, 8, 0));
            byId.put(admin.getId(), admin);
            return admin;
        }

        public int size() {
            return byId.size();
        }

        public AdminUser get(long id) {
            return byId.get(id);
        }

        @Override
        public Optional<AdminUser> findById(long adminId) {
            return Optional.ofNullable(byId.get(adminId));
        }

        @Override
        public Optional<AdminUser> findByUsername(String username) {
            return byId.values().stream()
                    .filter(a -> a.getUsername().equals(username))
                    .findFirst();
        }

        @Override
        public long count() {
            return byId.size();
        }

        @Override
        public void insert(AdminUser admin) {
            if (byId.containsKey(admin.getId())) {
                throw new IllegalStateException("替身：主键重复 id=" + admin.getId());
            }
            if (findByUsername(admin.getUsername()).isPresent()) {
                throw new org.springframework.dao.DuplicateKeyException(
                        "替身：uk_username 冲突 " + admin.getUsername());
            }
            byId.put(admin.getId(), admin);
        }

        @Override
        public void update(AdminUser admin) {
            AdminUser row = byId.get(admin.getId());
            if (row == null) {
                return;
            }
            row.setDisplayName(admin.getDisplayName());
            row.setRole(admin.getRole());
            row.setStatus(admin.getStatus());
        }

        @Override
        public void updatePasswordHash(long adminId, String passwordHash) {
            AdminUser row = byId.get(adminId);
            if (row != null) {
                row.setPasswordHash(passwordHash);
            }
        }

        @Override
        public void updateLoginState(long adminId, int failedAttempts, LocalDateTime lockedUntil,
                                     LocalDateTime lastLoginAt) {
            AdminUser row = byId.get(adminId);
            if (row == null) {
                return;
            }
            row.setFailedAttempts(failedAttempts);
            row.setLockedUntil(lockedUntil);
            row.setLastLoginAt(lastLoginAt);
        }

        @Override
        public List<AdminUser> page(Long beforeId, int limit) {
            return byId.values().stream()
                    .filter(a -> beforeId == null || a.getId() < beforeId)
                    .sorted(Comparator.comparing(AdminUser::getId).reversed())
                    .limit(Math.max(1, limit))
                    .toList();
        }

        @Override
        public void updateStatus(long adminId, AdminStatus status) {
            AdminUser row = byId.get(adminId);
            if (row != null) {
                row.setStatus(status);
            }
        }
    }

    public static final class Sessions implements AdminSessionRepository {

        private final Map<Long, AdminSession> byId = new LinkedHashMap<>();
        private int touchCount;

        public int size() {
            return byId.size();
        }

        public int touchCount() {
            return touchCount;
        }

        /** 直接塞一个会话（用于「已过期」「已登出」这类前置状态）。 */
        public AdminSession put(long adminId, String token, LocalDateTime expiresAt) {
            AdminSession session = new AdminSession();
            session.setId(next(byId));
            session.setAdminId(adminId);
            session.setTokenHash(OpaqueToken.hash(token));
            session.setExpiresAt(expiresAt);
            session.setCreatedAt(LocalDateTime.of(2026, 1, 1, 8, 0));
            byId.put(session.getId(), session);
            return session;
        }

        private static long next(Map<Long, AdminSession> rows) {
            return rows.keySet().stream().mapToLong(Long::longValue).max().orElse(2000L) + 1;
        }

        @Override
        public void insert(AdminSession session) {
            byId.put(session.getId(), session);
        }

        @Override
        public Optional<AdminSession> findByTokenHash(String tokenHash) {
            return byId.values().stream()
                    .filter(s -> s.getTokenHash().equals(tokenHash))
                    .findFirst();
        }

        @Override
        public void touch(long sessionId, LocalDateTime at) {
            touchCount++;
            AdminSession row = byId.get(sessionId);
            if (row != null) {
                row.setLastSeenAt(at);
            }
        }

        @Override
        public boolean deleteByTokenHash(String tokenHash) {
            return byId.values().removeIf(s -> s.getTokenHash().equals(tokenHash));
        }

        @Override
        public int deleteByAdminId(long adminId) {
            int before = byId.size();
            byId.values().removeIf(s -> s.getAdminId() == adminId);
            return before - byId.size();
        }

        @Override
        public int deleteExpired(LocalDateTime before) {
            int size = byId.size();
            byId.values().removeIf(s -> s.getExpiresAt().isBefore(before));
            return size - byId.size();
        }
    }

    public static final class Audits implements AdminAuditLogRepository {

        private final List<AdminAuditLog> rows = new ArrayList<>();

        public List<AdminAuditLog> all() {
            return List.copyOf(rows);
        }

        public List<AdminAuditLog> withAction(String action) {
            return rows.stream().filter(r -> action.equals(r.getAction())).toList();
        }

        public AdminAuditLog last() {
            return rows.isEmpty() ? null : rows.get(rows.size() - 1);
        }

        public int size() {
            return rows.size();
        }

        @Override
        public void insert(AdminAuditLog row) {
            rows.add(row);
        }

        @Override
        public List<AdminAuditLog> page(Long beforeId, int limit, Long adminId, String action,
                                        String targetType, Long targetId) {
            return rows.stream()
                    .filter(r -> beforeId == null || r.getId() < beforeId)
                    .filter(r -> adminId == null || r.getAdminId().equals(adminId))
                    .filter(r -> action == null || action.isBlank() || action.equals(r.getAction()))
                    .filter(r -> targetType == null || targetType.isBlank()
                            || targetType.equals(r.getTargetType()))
                    .filter(r -> targetId == null || targetId.equals(r.getTargetId()))
                    .sorted(Comparator.comparing(AdminAuditLog::getId).reversed())
                    .limit(Math.max(1, limit))
                    .toList();
        }
    }

    /** 便捷断言：某次调用抛出的错误码。 */
    public static ErrorCode codeOf(Runnable action) {
        TmException e = org.assertj.core.api.Assertions.catchThrowableOfType(
                action::run, TmException.class);
        org.assertj.core.api.Assertions.assertThat(e)
                .as("应当抛出 TmException").isNotNull();
        return e.errorCode();
    }
}
