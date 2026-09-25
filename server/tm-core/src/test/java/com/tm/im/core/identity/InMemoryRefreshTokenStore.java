package com.tm.im.core.identity;

import com.tm.im.common.crypto.RefreshTokens;
import com.tm.im.domain.repository.RefreshSession;
import com.tm.im.domain.repository.RefreshTokenStore;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 内存版 {@link RefreshTokenStore}。
 *
 * <p><b>它必须复刻「一次性」这条语义</b>，否则测试会给出一条生产上不存在的保证：
 * {@link #consume} 在这里就把条目删掉（与真实实现的 {@code GETDEL} 对应），
 * 所以「同一个 refresh_token 用两次」的用例在替身上也会失败。
 * 这正是替身容易骗人的地方——它验证的是<b>接口语义</b>，
 * 而「GETDEL 真的是原子的」只能由真实 Redis 的集成测试覆盖。
 */
class InMemoryRefreshTokenStore implements RefreshTokenStore {

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private int issuedCount;

    /** 记录每次签发时的 TTL，供断言「用的是配置值」而不是某个写死的数。 */
    Duration lastTtl;

    private record Entry(RefreshSession session, Duration ttl) {
    }

    int issuedCount() {
        return issuedCount;
    }

    boolean contains(String plainToken) {
        return entries.containsKey(RefreshTokens.hash(plainToken));
    }

    @Override
    public String issue(long actorId, String deviceId, Duration ttl) {
        String plain = RefreshTokens.generate();
        entries.put(RefreshTokens.hash(plain), new Entry(new RefreshSession(actorId, deviceId), ttl));
        lastTtl = ttl;
        issuedCount++;
        return plain;
    }

    @Override
    public Optional<RefreshSession> consume(String plainToken) {
        if (plainToken == null) {
            return Optional.empty();
        }
        Entry entry = entries.remove(RefreshTokens.hash(plainToken));
        return entry == null ? Optional.empty() : Optional.of(entry.session());
    }

    @Override
    public Optional<RefreshSession> peek(String plainToken) {
        if (plainToken == null) {
            return Optional.empty();
        }
        Entry entry = entries.get(RefreshTokens.hash(plainToken));
        return entry == null ? Optional.empty() : Optional.of(entry.session());
    }

    @Override
    public void revoke(String plainToken) {
        if (plainToken != null) {
            entries.remove(RefreshTokens.hash(plainToken));
        }
    }
}
