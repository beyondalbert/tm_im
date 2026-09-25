package com.tm.im.storage.identity;

import com.tm.im.common.crypto.RefreshTokens;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.common.json.Json;
import com.tm.im.domain.repository.RefreshSession;
import com.tm.im.storage.it.RedisItConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * refresh_token 存放的 Redis 语义 —— <b>真实 Redis</b>（02-auth.md §2.3）。
 *
 * <p><b>为什么这些断言不能用手写替身</b>：{@code InMemoryRefreshTokenStore}
 * 验证的是「接口语义」（用它两次会失败），而这里验证的是<b>实现语义</b>：
 * TTL 真的被设上了、{@code GETDEL} 真的把键取走了、值真的是那份 JSON。
 * 替身再逼真也证明不了「Redis 上真的发生了这件事」——而线上出问题的永远是后者。
 *
 * <p>键名按<b>字面量</b>断言：{@code tm:rt:{sha256}} 是跨进程契约
 * （运维要按它排查、清会话脚本要按它操作），改名就该让测试红。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = RedisItConfig.class)
@Import(RedisItConfig.class)
class RedisRefreshTokenStoreIT {

    private static final long ACTOR_ID = 9_000_000_000_001L;
    private static final String DEVICE = "it-junit-device";

    @Autowired
    StringRedisTemplate redis;

    private RedisRefreshTokenStore store;

    private final List<String> issued = new ArrayList<>();

    private RedisRefreshTokenStore store() {
        if (store == null) {
            store = new RedisRefreshTokenStore(redis);
        }
        return store;
    }

    @AfterEach
    void cleanUp() {
        for (String token : issued) {
            redis.delete(keyOf(token));
        }
        issued.clear();
    }

    @Test
    @DisplayName("签发：键名 tm:rt:{sha256}，值是含 actorId/deviceId 的 JSON，TTL 生效")
    void issueWritesHashedKeyWithTtl() {
        String token = store().issue(ACTOR_ID, DEVICE, Duration.ofMinutes(10));
        issued.add(token);

        String key = keyOf(token);
        assertThat(key).startsWith("tm:rt:").hasSize("tm:rt:".length() + 64);
        // 明文绝不能出现在键里：能读到 Redis 快照的人不该直接拿到可用的长期凭证
        assertThat(key).doesNotContain(token);

        String raw = redis.opsForValue().get(key);
        assertThat(raw).isNotNull();
        assertThat(Json.read(raw, java.util.Map.class))
                .containsEntry("actorId", ACTOR_ID)
                .containsEntry("deviceId", DEVICE);

        Long ttl = redis.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(ttl).isBetween(1L, 600L);
    }

    @Test
    @DisplayName("设备标识缺省时不写进值里（Json 的 NON_NULL），读回来仍是 null")
    void deviceIdIsOptional() {
        String token = store().issue(ACTOR_ID, null, Duration.ofMinutes(10));
        issued.add(token);

        assertThat(redis.opsForValue().get(keyOf(token))).doesNotContain("deviceId");
        assertThat(store().consume(token).orElseThrow().deviceId()).isNull();
    }

    @Test
    @DisplayName("consume 是一次性的：第二次拿到空（这就是防重放的全部依据）")
    void consumeIsOneShot() {
        String token = store().issue(ACTOR_ID, DEVICE, Duration.ofMinutes(10));
        issued.add(token);

        Optional<RefreshSession> first = store().consume(token);
        assertThat(first).isPresent();
        assertThat(first.get().actorId()).isEqualTo(ACTOR_ID);

        assertThat(store().consume(token)).isEmpty();
        // 键也确实没了，而不是被标记成「已用」——GETDEL 的语义
        assertThat(redis.hasKey(keyOf(token))).isFalse();
    }

    @Test
    @DisplayName("peek 只读不消耗：登出前的「这凭证是不是你的」检查不能把会话用掉")
    void peekDoesNotConsume() {
        String token = store().issue(ACTOR_ID, DEVICE, Duration.ofMinutes(10));
        issued.add(token);

        assertThat(store().peek(token)).isPresent();
        assertThat(store().peek(token)).isPresent();
        assertThat(redis.hasKey(keyOf(token))).isTrue();

        // peek 之后仍能正常消费
        assertThat(store().consume(token)).isPresent();
    }

    @Test
    @DisplayName("revoke 删键且幂等（重复登出是弱网常态）")
    void revokeIsIdempotent() {
        String token = store().issue(ACTOR_ID, DEVICE, Duration.ofMinutes(10));

        store().revoke(token);
        assertThat(redis.hasKey(keyOf(token))).isFalse();
        store().revoke(token);
        assertThat(store().consume(token)).isEmpty();
    }

    @Test
    @DisplayName("空 / null / 不存在的凭证一律返回空，绝不抛（对外都是 40104）")
    void unknownTokensAreEmptyNotErrors() {
        assertThat(store().consume(null)).isEmpty();
        assertThat(store().consume("")).isEmpty();
        assertThat(store().consume("rt_never_issued")).isEmpty();
        assertThat(store().peek(null)).isEmpty();
        store().revoke(null);
        store().revoke("rt_never_issued");
    }

    @Test
    @DisplayName("值被写坏时按无效处理（不抛）：一次数据污染不该让所有刷新请求 500")
    void corruptValueIsTreatedAsInvalid() {
        // 手工造一个键，值不是合法 JSON（模拟被人改过 / 上一版格式）
        String token = RefreshTokens.generate();
        issued.add(token);
        redis.opsForValue().set(keyOf(token), "{ this is not json", Duration.ofMinutes(5));
        assertThat(store().consume(token)).isEmpty();

        // 合法 JSON 但缺 actorId 也是同一处理
        String second = RefreshTokens.generate();
        issued.add(second);
        redis.opsForValue().set(keyOf(second), "{\"deviceId\":\"x\"}", Duration.ofMinutes(5));
        assertThat(store().consume(second)).isEmpty();
    }

    @Test
    @DisplayName("零 / 负 TTL 直接拒绝：无 TTL 的会话键是一条永久凭证")
    void nonPositiveTtlIsRejected() {
        assertThatThrownBy(() -> store().issue(ACTOR_ID, DEVICE, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().issue(ACTOR_ID, DEVICE, Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().issue(ACTOR_ID, DEVICE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Redis 不可用：四个方法都回 50002（可重试），绝不伪装成「凭证无效」")
    void redisUnavailableBecomesCacheUnavailable() {
        // 指向一个必然拒绝连接的地址（本机 1 号端口）。这不是「可疑的模拟」：
        // 连接被 refused 是真实的故障形态，而且它是确定性的、不会偶发。
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration("127.0.0.1", 1);
        LettuceConnectionFactory broken = new LettuceConnectionFactory(cfg);
        broken.afterPropertiesSet();
        StringRedisTemplate downTemplate = new StringRedisTemplate(broken);
        downTemplate.afterPropertiesSet();
        try {
            RedisRefreshTokenStore down = new RedisRefreshTokenStore(downTemplate);

            assertThatThrownBy(() -> down.issue(ACTOR_ID, DEVICE, Duration.ofMinutes(5)))
                    .isInstanceOf(TmException.class)
                    .extracting(e -> ((TmException) e).errorCode())
                    .isEqualTo(ErrorCode.CACHE_UNAVAILABLE);
            assertThatThrownBy(() -> down.consume("rt_some_token"))
                    .isInstanceOf(TmException.class)
                    .extracting(e -> ((TmException) e).errorCode())
                    .isEqualTo(ErrorCode.CACHE_UNAVAILABLE);
            assertThatThrownBy(() -> down.peek("rt_some_token"))
                    .isInstanceOf(TmException.class)
                    .extracting(e -> ((TmException) e).errorCode())
                    .isEqualTo(ErrorCode.CACHE_UNAVAILABLE);
            assertThatThrownBy(() -> down.revoke("rt_some_token"))
                    .isInstanceOf(TmException.class)
                    .extracting(e -> ((TmException) e).errorCode())
                    .isEqualTo(ErrorCode.CACHE_UNAVAILABLE);
        } finally {
            broken.destroy();
        }
    }

    private static String keyOf(String token) {
        return RedisRefreshTokenStore.KEY_PREFIX + RefreshTokens.hash(token);
    }
}
