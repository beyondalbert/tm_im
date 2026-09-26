package com.tm.im.channel.support;

import com.tm.im.common.crypto.Digests;
import com.tm.im.core.identity.IdentityService;
import com.tm.im.core.identity.JwtTokenService;
import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.entity.ActorSecret;
import com.tm.im.domain.enums.ActorStatus;
import com.tm.im.domain.enums.ActorType;
import com.tm.im.domain.enums.SecretType;
import com.tm.im.domain.repository.ActorRepository;
import com.tm.im.domain.repository.ActorSecretRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版身份数据源，供「不需要真实 MySQL」的测试使用。
 *
 * <p>它是真实仓储接口的一个诚实实现，不是 mock：<b>不返回宽松的默认值</b>。
 * 查询未登记的 actor 就是 {@code Optional.empty()}，未实现的写操作直接抛
 * {@link UnsupportedOperationException} —— 如果某条代码路径偷偷调了不该调的方法，
 * 测试会立刻炸掉，而不是拿到一个恰好好用的假数据继续变绿。
 *
 * <p>api_key 走的是与生产完全相同的路径：登记时算 {@code sha256(key)}，
 * 鉴权时按哈希反查（{@code actor_secret.secret_hash} 上那条唯一索引的语义）。
 */
public final class InMemoryIdentity {

    private final Map<Long, Actor> actorsById = new ConcurrentHashMap<>();
    private final Map<String, Long> actorsByHandle = new ConcurrentHashMap<>();
    private final Map<String, Long> actorsBySecretHash = new ConcurrentHashMap<>();

    public final JwtTokenService tokens;
    public final IdentityService service;

    public InMemoryIdentity(String jwtSecret) {
        this.tokens = new JwtTokenService(jwtSecret);
        this.service = new IdentityService(actorRepository(), secretRepository(), tokens);
    }

    /** 登记一个 Actor。{@code status} 决定它是能登录还是被停用。 */
    public InMemoryIdentity actor(long actorId, String handle, ActorType type, ActorStatus status) {
        Actor actor = new Actor();
        actor.setId(actorId);
        actor.setHandle(handle);
        actor.setActorType(type);
        actor.setStatus(status);
        actorsById.put(actorId, actor);
        actorsByHandle.put(handle, actorId);
        return this;
    }

    public InMemoryIdentity human(long actorId, String handle) {
        return actor(actorId, handle, ActorType.HUMAN, ActorStatus.ACTIVE);
    }

    public InMemoryIdentity agent(long actorId, String handle) {
        return actor(actorId, handle, ActorType.AGENT, ActorStatus.ACTIVE);
    }

    /** 等价于「把一把 api_key 交给某个 Actor」：登记的是哈希，明文不落库。 */
    public InMemoryIdentity apiKey(String plaintextKey, long actorId) {
        actorsBySecretHash.put(Digests.sha256Hex(plaintextKey), actorId);
        return this;
    }

    public IdentityService service() {
        return service;
    }

    public JwtTokenService tokens() {
        return tokens;
    }

    /** 签一个可用 2 小时的 JWT，模拟 REST 登录的产物。 */
    public String jwt(long actorId) {
        Actor actor = actorsById.get(actorId);
        if (actor == null) {
            throw new IllegalArgumentException("未登记的 actorId=" + actorId);
        }
        return tokens.issue(actorId, actor.getHandle(), actor.getActorType(), java.time.Duration.ofHours(2));
    }

    private ActorRepository actorRepository() {
        return new ActorRepository() {
            @Override
            public Optional<Actor> findById(long actorId) {
                return Optional.ofNullable(actorsById.get(actorId));
            }

            @Override
            public Optional<Actor> findByHandle(String handle) {
                Long id = actorsByHandle.get(handle);
                return id == null ? Optional.empty() : Optional.ofNullable(actorsById.get(id));
            }

            @Override
            public boolean existsHandle(String handle) {
                return actorsByHandle.containsKey(handle);
            }

            @Override
            public Actor insert(Actor actor) {
                throw new UnsupportedOperationException(
                        "长连接路径不应写 Actor 表：注册流程属于 REST，测试里若走到这里说明分层被破坏了");
            }

            @Override
            public List<Actor> findByIds(List<Long> actorIds) {
                List<Actor> found = new ArrayList<>();
                for (Long id : actorIds) {
                    Actor actor = actorsById.get(id);
                    if (actor != null) {
                        found.add(actor);
                    }
                }
                return found;
            }

            @Override
            public void update(Actor actor) {
                throw new UnsupportedOperationException(
                        "长连接路径不应改 Actor 表：资料修改属于 REST");
            }
        };
    }

    private ActorSecretRepository secretRepository() {
        return new ActorSecretRepository() {
            @Override
            public Optional<ActorSecret> find(long actorId, SecretType secretType) {
                return Optional.empty();
            }

            @Override
            public Optional<Long> findActorIdByHash(SecretType secretType, String secretHash) {
                // 生产实现只认 API_KEY_HASH 这一类；这里同样不忽略 secret_type，
                // 否则「将来新增一类凭证时误用同一张表」会在这里被悄悄放过。
                if (secretType != SecretType.API_KEY_HASH) {
                    return Optional.empty();
                }
                return Optional.ofNullable(actorsBySecretHash.get(secretHash));
            }

            @Override
            public void upsert(ActorSecret secret) {
                throw new UnsupportedOperationException("长连接路径不应写凭据表");
            }

            @Override
            public boolean delete(long actorId, SecretType secretType) {
                throw new UnsupportedOperationException("长连接路径不应删凭据");
            }
        };
    }
}
