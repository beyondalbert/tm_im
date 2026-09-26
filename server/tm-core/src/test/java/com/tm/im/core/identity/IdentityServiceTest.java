package com.tm.im.core.identity;

import com.tm.im.common.crypto.Digests;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.entity.ActorSecret;
import com.tm.im.domain.enums.ActorStatus;
import com.tm.im.domain.enums.ActorType;
import com.tm.im.domain.enums.SecretType;
import com.tm.im.domain.repository.ActorRepository;
import com.tm.im.domain.repository.ActorSecretRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 鉴权分派与失败路径。
 *
 * <p>仓储用<b>内存桩</b>而不是 Mockito。理由：这里要验证的是「分派到哪条路径、
 * 失败时给哪个错误码」这类<b>语义</b>，桩实现让断言直接可读；同时桩本身就是
 * 接口语义的一份可执行说明（例如「按哈希反查不到返回 empty」），
 * 接口若被改动，桩会先编译失败而不是让测试在运行期变得无意义。
 */
class IdentityServiceTest {

    private static final String SECRET = "test-secret-0123456789abcdef0123456789abcdef";
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final String VALID_API_KEY = "sk_live_9f2c1d7a4b8e3f60";

    private final Fixture fx = new Fixture();

    @Test
    @DisplayName("JWT → 人类；api_key → Agent：两类凭证走同一个出口，拿到同一种结构")
    void bothCredentialKindsProduceSameShape() {
        fx.actor(11L, ActorType.HUMAN, ActorStatus.ACTIVE);
        String jwt = fx.service().tokens()
                .issue(11L, "human_11", ActorType.HUMAN, Duration.ofHours(1));

        AuthContext byJwt = fx.service().authenticate(jwt, "web-chrome-131");
        assertThat(byJwt.actorId()).isEqualTo(11L);
        assertThat(byJwt.kind()).isEqualTo(CredentialKind.JWT);
        assertThat(byJwt.isAgent()).isFalse();

        fx.actor(22L, ActorType.AGENT, ActorStatus.ACTIVE);
        fx.apiKey(VALID_API_KEY, 22L);

        AuthContext byKey = fx.service().authenticate(VALID_API_KEY, "agent-python-1.0");
        assertThat(byKey.actorId()).isEqualTo(22L);
        assertThat(byKey.handle()).isEqualTo("bot_22");
        assertThat(byKey.kind()).isEqualTo(CredentialKind.API_KEY);
        assertThat(byKey.isAgent()).isTrue();
        // deviceId 原样带出，长连接靠它做「同账号多端」判定
        assertThat(byKey.deviceId()).isEqualTo("agent-python-1.0");
    }

    @Test
    @DisplayName("api_key 必须原样命中：正文大小写、多一个字符都不行")
    void apiKeyLookupIsExact() {
        fx.actor(22L, ActorType.AGENT, ActorStatus.ACTIVE);
        fx.apiKey(VALID_API_KEY, 22L);

        assertThat(fx.service().authenticate(VALID_API_KEY, null).actorId()).isEqualTo(22L);

        // 只改正文的大小写（前缀保持不变），否则它会不再是 api_key 而是被当成 JWT
        String bodyUpper = VALID_API_KEY.substring(0, 8) + VALID_API_KEY.substring(8).toUpperCase();
        assertThat(fx.code(() -> fx.service().authenticate(bodyUpper, null)))
                .isEqualTo(ErrorCode.INVALID_API_KEY);
        assertThat(fx.code(() -> fx.service().authenticate(VALID_API_KEY + "0", null)))
                .isEqualTo(ErrorCode.INVALID_API_KEY);
    }

    @Test
    @DisplayName("失败路径的错误码与 02-auth.md §4 的表一致")
    void failureCodesMatchDoc() {
        fx.actor(11L, ActorType.HUMAN, ActorStatus.ACTIVE);
        fx.actor(33L, ActorType.HUMAN, ActorStatus.SUSPENDED);
        fx.apiKey(VALID_API_KEY, 22L);

        // 空凭证 → 40102（不是 40101：40101 专指「没带 Authorization 头」，
        // 长连接里不存在请求头这个概念）
        assertThat(fx.code(() -> fx.service().authenticate(null, null)))
                .isEqualTo(ErrorCode.INVALID_TOKEN_FORMAT);
        assertThat(fx.code(() -> fx.service().authenticate("  ", null)))
                .isEqualTo(ErrorCode.INVALID_TOKEN_FORMAT);

        // 伪造 JWT → 40102
        assertThat(fx.code(() -> fx.service().authenticate("not.a.jwt", null)))
                .isEqualTo(ErrorCode.INVALID_TOKEN_FORMAT);

        // 过期 JWT → 40103
        String expired = fx.service().tokens()
                .issue(11L, "human_11", ActorType.HUMAN, Duration.ofSeconds(1));
        IdentityService later = fx.serviceAt(T0.plusSeconds(10));
        assertThat(fx.code(() -> later.authenticate(expired, null))).isEqualTo(ErrorCode.TOKEN_EXPIRED);

        // 不存在的 api_key → 40105
        assertThat(fx.code(() -> fx.service().authenticate("sk_live_deadbeefdeadbeef", null)))
                .isEqualTo(ErrorCode.INVALID_API_KEY);

        // 账号被停用 → 40301（凭证本身合法，是账号不能用）
        String suspended = fx.service().tokens()
                .issue(33L, "human_33", ActorType.HUMAN, Duration.ofHours(1));
        assertThat(fx.code(() -> fx.service().authenticate(suspended, null)))
                .isEqualTo(ErrorCode.ACCOUNT_SUSPENDED);

        // 凭证合法但账号已不存在 → 40401
        String ghost = fx.service().tokens()
                .issue(999L, "deleted", ActorType.HUMAN, Duration.ofHours(1));
        assertThat(fx.code(() -> fx.service().authenticate(ghost, null)))
                .isEqualTo(ErrorCode.ACTOR_NOT_FOUND);
    }

    @Test
    @DisplayName("停用状态每次从库里读，不信任 token 里的快照")
    void suspendedIsReadFromStoreNotFromToken() {
        Actor a = fx.actor(11L, ActorType.HUMAN, ActorStatus.ACTIVE);
        String jwt = fx.service().tokens().issue(11L, "human_11", ActorType.HUMAN, Duration.ofHours(1));
        assertThat(fx.service().authenticate(jwt, null).actorId()).isEqualTo(11L);

        // 同一个 token（未过期），账号在签发之后被封禁 —— 必须立刻被拒。
        // 若哪天有人为了「省一次查询」把状态塞进 token，这条会失败。
        a.setStatus(ActorStatus.SUSPENDED);
        assertThat(fx.code(() -> fx.service().authenticate(jwt, null)))
                .isEqualTo(ErrorCode.ACCOUNT_SUSPENDED);
    }

    @Test
    @DisplayName("错误信息里不出现完整凭证（只留前 8 位用于比对日志与库）")
    void credentialIsNotLeakedInErrors() {
        String apiKey = "sk_live_" + "0123456789abcdefSECRETPART";
        TmException e = catchThrowableOfType(() -> fx.service().authenticate(apiKey, null), TmException.class);

        assertThat(e.detail()).doesNotContain("SECRETPART").contains("sk_live_");
        assertThat(e.getMessage()).doesNotContain(apiKey);
    }

    @Test
    @DisplayName("前缀判定：sk_ 走 api_key（不做 JWT 解析），其余走 JWT")
    void credentialKindIsDecidedByPrefixOnly() {
        assertThat(CredentialKind.of("sk_live_abc")).isEqualTo(CredentialKind.API_KEY);
        assertThat(CredentialKind.of("sk_test_abc")).isEqualTo(CredentialKind.API_KEY);
        assertThat(CredentialKind.of("eyJhbGciOiJIUzI1NiJ9.e30.x")).isEqualTo(CredentialKind.JWT);
        assertThat(CredentialKind.of(null)).isEqualTo(CredentialKind.JWT);
        // 只有前缀完全匹配才算 api_key：'sk' 后少了下划线应走 JWT 分支
        assertThat(CredentialKind.of("sk_live")).isEqualTo(CredentialKind.API_KEY);
    }

    // ==================== 内存桩 ====================

    private static final class Fixture {

        private final Map<Long, Actor> actors = new LinkedHashMap<>();
        private final Map<String, Long> hashToActor = new LinkedHashMap<>();

        Actor actor(long id, ActorType type, ActorStatus status) {
            Actor a = new Actor();
            a.setId(id);
            a.setHandle((type == ActorType.AGENT ? "bot_" : "human_") + id);
            a.setActorType(type);
            a.setStatus(status);
            actors.put(id, a);
            return a;
        }

        void apiKey(String plain, long actorId) {
            hashToActor.put(SecretType.API_KEY_HASH + ":" + Digests.sha256Hex(plain), actorId);
        }

        IdentityService service() {
            return serviceAt(T0);
        }

        IdentityService serviceAt(Instant now) {
            return new IdentityService(actorRepo(), secretRepo(),
                    new JwtTokenService(SECRET, Clock.fixed(now, ZoneOffset.UTC)));
        }

        ErrorCode code(Runnable r) {
            TmException e = catchThrowableOfType(r::run, TmException.class);
            assertThat(e).as("应当抛出 TmException").isNotNull();
            return e.errorCode();
        }

        private ActorRepository actorRepo() {
            return new ActorRepository() {
                @Override
                public Optional<Actor> findById(long actorId) {
                    return Optional.ofNullable(actors.get(actorId));
                }

                @Override
                public Optional<Actor> findByHandle(String handle) {
                    return actors.values().stream()
                            .filter(a -> a.getHandle().equals(handle)).findFirst();
                }

                @Override
                public boolean existsHandle(String handle) {
                    return findByHandle(handle).isPresent();
                }

                @Override
                public Actor insert(Actor actor) {
                    actors.put(actor.getId(), actor);
                    return actor;
                }

                @Override
                public List<Actor> findByIds(List<Long> actorIds) {
                    List<Actor> out = new ArrayList<>();
                    for (Long id : actorIds) {
                        Optional.ofNullable(actors.get(id)).ifPresent(out::add);
                    }
                    return out;
                }

                @Override
                public void update(Actor actor) {
                    actors.put(actor.getId(), actor);
                }
            };
        }

        private ActorSecretRepository secretRepo() {
            return new ActorSecretRepository() {
                @Override
                public Optional<ActorSecret> find(long actorId, SecretType secretType) {
                    return Optional.empty();
                }

                @Override
                public Optional<Long> findActorIdByHash(SecretType secretType, String secretHash) {
                    return Optional.ofNullable(hashToActor.get(secretType + ":" + secretHash));
                }

                @Override
                public void upsert(ActorSecret secret) {
                    hashToActor.put(secret.getSecretType() + ":" + secret.getSecretHash(),
                            secret.getActorId());
                }

                @Override
                public boolean delete(long actorId, SecretType secretType) {
                    return false;
                }
            };
        }
    }
}
