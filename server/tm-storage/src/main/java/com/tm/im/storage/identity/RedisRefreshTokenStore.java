package com.tm.im.storage.identity;

import com.tm.im.common.crypto.RefreshTokens;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.common.json.Json;
import com.tm.im.domain.repository.RefreshSession;
import com.tm.im.domain.repository.RefreshTokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * refresh_token 存放的 Redis 实现。
 *
 * <pre>
 *   key   tm:rt:{sha256hex(明文)}
 *   value {"actorId":1001,"deviceId":"web-chrome-131"}      （JSON，deviceId 可缺）
 *   TTL   tm.identity.refresh-token-ttl（默认 30 天）
 * </pre>
 *
 * <p><b>为什么存哈希而不是明文</b>：与 {@code actor_secret} 里的 api_key 同理——
 * 能读到 Redis 快照的人不应该直接拿到一批可用的长期凭证。键名本身也因此
 * 不泄漏「谁的会话」：{@code tm:rt:...} 后面那段是哈希，不是 actorId。
 *
 * <p><b>为什么用 TTL 而不是「带过期时间的记录 + 定期清理」</b>：后者需要
 * 一个清理任务，而清理任务一旦没部署（本仓库的归档任务就还没做，见 DESIGN §10.2），
 * 库会一直长。TTL 由 Redis 自己执行，没有「运维忘了跑」这个失败模式。
 *
 * <p><b>过期与不存在为什么不区分</b>：两者对外都是 40104
 * （02-auth.md §4），能区分只会泄漏「这个凭证曾经存在过」。
 * 而 {@link #consume} 用的 {@code GETDEL} 也让「用过」与「过期」在实现上
 * 就是同一件事——不需要额外记录「已使用」标记。
 *
 * <p><b>Redis 故障为什么<b>不</b>降级</b>：本类的三个方法都不能「往下退一步」。
 * <ul>
 *   <li>{@link #issue} 退不了：签发一个存不下去的 refresh_token 等于给客户端
 *       一个假承诺，用户会在 2 小时后（access token 到期时）发现自己被登出，
 *       而那时没人能把这个现象和「登录时 Redis 抖了一下」联系起来；</li>
 *   <li>{@link #consume} 退不了：把它当成「凭证无效」会让一次缓存抖动
 *       表现为「所有人都被登出」；</li>
 *   <li>{@link #revoke} 也退不了：返回成功但没删掉，用户以为退出了，凭证还在。</li>
 * </ul>
 * 所以三个方法都把基础设施异常翻译成 {@code 50002 CACHE_UNAVAILABLE}
 * （retryable，02-auth.md 的表里客户端对 5xxxx 的处理是退避重试）。
 * 这正是长连接那边「Redis 异常只降级」策略的<b>反面</b>，而两者并不矛盾：
 * 那边降级后仍有一条正确路径（等重连后按 last_seq 补齐），这边没有。
 */
@Repository
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RedisRefreshTokenStore.class);

    /**
     * 键前缀，与 DESIGN §10.4 的 key 表同一口径。
     *
     * <p>刻意 {@code public}：测试要按字面量断言它（改名就红），
     * 运维清会话也要按同一个名字操作。包内私有会让各处出现本地副本。
     */
    public static final String KEY_PREFIX = "tm:rt:";

    private final StringRedisTemplate redis;

    public RedisRefreshTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public String issue(long actorId, String deviceId, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            // 没有 TTL 的会话键是一条永久凭证 —— 这属于配置写错（tm.identity.refresh-token-ttl），
            // 宁可启动之后立刻显形，也不要静默地存下一条永不失效的凭证。
            throw new IllegalArgumentException("refresh token ttl 必须为正: " + ttl);
        }
        String plain = RefreshTokens.generate();
        String json = Json.write(new Stored(actorId, deviceId));
        try {
            redis.opsForValue().set(key(plain), json, ttl);
        } catch (DataAccessException e) {
            throw unavailable("issue", e);
        }
        return plain;
    }

    @Override
    public Optional<RefreshSession> consume(String plainToken) {
        if (plainToken == null || plainToken.isBlank()) {
            // 空凭证不必去问 Redis，但也不报错：它是「没有带 refresh_token」，
            // 与「带了但无效」一样归 40104（调用方负责翻译）。
            return Optional.empty();
        }
        String value;
        try {
            // GETDEL 是关键：它把「读」与「删」合成一次原子操作。
            // 拆成 get 再 delete 的话，两个并发刷新请求会同时命中同一个凭证，
            // 各自换出一对新 token —— 这正是重放攻击的形态，而它在测试里
            // 永远不会偶发失败（窗口只有微秒级），只能靠这里不写错来避免。
            value = redis.opsForValue().getAndDelete(key(plainToken));
        } catch (DataAccessException e) {
            throw unavailable("consume", e);
        }
        return parse(value);
    }

    @Override
    public Optional<RefreshSession> peek(String plainToken) {
        if (plainToken == null || plainToken.isBlank()) {
            return Optional.empty();
        }
        String value;
        try {
            value = redis.opsForValue().get(key(plainToken));
        } catch (DataAccessException e) {
            throw unavailable("peek", e);
        }
        return parse(value);
    }

    @Override
    public void revoke(String plainToken) {
        if (plainToken == null || plainToken.isBlank()) {
            return;
        }
        try {
            redis.delete(key(plainToken));
        } catch (DataAccessException e) {
            throw unavailable("revoke", e);
        }
    }

    private static String key(String plainToken) {
        return KEY_PREFIX + RefreshTokens.hash(plainToken);
    }

    /**
     * 值坏了（被手工改过、或上一版格式不同）时当作「没有这个会话」。
     *
     * <p>与 {@code PasswordHashes.verify} 的处理相反（那里遇到坏值要抛）：
     * 这里坏掉的最坏后果是「这个用户重新登录一次」，而抛异常会让一次
     * 数据污染升级成所有刷新请求 500。刻意记 WARN，让「库被人动过」留下痕迹。
     */
    private static Optional<RefreshSession> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            Stored stored = Json.read(value, Stored.class);
            if (stored == null || stored.actorId() == null || stored.actorId() <= 0) {
                log.warn("tm:rt 值缺少可用的 actorId，按无效处理: {}", value);
                return Optional.empty();
            }
            return Optional.of(new RefreshSession(stored.actorId(), stored.deviceId()));
        } catch (RuntimeException e) {
            log.warn("tm:rt 值不是合法 JSON，按无效处理: {}", value, e);
            return Optional.empty();
        }
    }

    private static TmException unavailable(String action, DataAccessException cause) {
        log.error("Redis 不可用，refresh token {} 失败", action, cause);
        return new TmException(ErrorCode.CACHE_UNAVAILABLE, "refresh token " + action, cause);
    }

    /** 存储形态。字段名即 JSON 键名，改它等于让所有存量会话失效（老值解析不出 actorId）。 */
    private record Stored(Long actorId, String deviceId) {
    }
}
