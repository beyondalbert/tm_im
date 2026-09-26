package com.tm.im.core.agent;

import com.tm.im.common.crypto.AgentCredentials;
import com.tm.im.common.crypto.Digests;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.common.id.IdGenerator;
import com.tm.im.common.json.Json;
import com.tm.im.core.conversation.InMemoryActors;
import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.entity.ActorSecret;
import com.tm.im.domain.entity.AgentProfile;
import com.tm.im.domain.enums.ActorStatus;
import com.tm.im.domain.enums.ActorType;
import com.tm.im.domain.enums.PushMode;
import com.tm.im.domain.enums.SecretType;
import com.tm.im.domain.repository.ActorSecretRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AgentService} 的规则（03-rest-api.md §7 / 02-auth.md §3）。
 *
 * <p>这个类里最重要的两条断言不是「能不能创建成功」，而是两处容易做错的取舍：
 * <ol>
 *   <li><b>webhook_secret 存明文、api_key 存哈希</b>：前者要用于 HMAC 签名
 *       （哈希不可逆，用它签出来的东西对方验不了），后者服务端从不需要明文。
 *       把它们都存哈希是个「看起来更安全」的错误实现；</li>
 *   <li><b>停用 Agent 时删掉 api_key 哈希</b>看起来更彻底，但会让客户端拿到
 *       {@code 40105}（「检查或轮换」——而 Agent 无权轮换），而不是
 *       {@code 40301}（「停止重试、告诉用户」）。本类直接断言哈希仍在。</li>
 * </ol>
 */
class AgentServiceTest {

    private static final long ALICE = 1001L;
    private static final long BOB = 1002L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private InMemoryActors actors;
    private MapSecrets secrets;
    private InMemoryAgentProfiles profiles;
    private AgentProperties properties;
    private AgentService service;

    @BeforeEach
    void setUp() {
        actors = new InMemoryActors();
        secrets = new MapSecrets();
        profiles = new InMemoryAgentProfiles();
        properties = new AgentProperties();
        actors.put(ALICE, "alice");
        actors.put(BOB, "bob");
        service = new AgentService(actors, secrets, profiles, new Sequential(), properties, ZONE);
    }

    // ================================================================ 创建

    @Test
    @DisplayName("创建：写 actor(AGENT) + profile + 凭据；api_key 存哈希、webhook_secret 存明文")
    void createWritesActorProfileAndCredentials() {
        AgentService.Created created = service.create(ALICE, command("weather_bot", PushMode.WEBHOOK));

        Actor agent = created.actor();
        assertThat(agent.getActorType()).isEqualTo(ActorType.AGENT);
        assertThat(agent.getHandle()).isEqualTo("weather_bot");
        assertThat(agent.getStatus()).isEqualTo(ActorStatus.ACTIVE);
        assertThat(actors.findById(agent.getId())).isPresent();

        AgentProfile profile = created.profile();
        assertThat(profile.getOwnerActor()).isEqualTo(ALICE);
        assertThat(profile.getPushMode()).isEqualTo(PushMode.WEBHOOK);
        assertThat(profile.getEndpointUrl()).isEqualTo("https://agent.example.com/hook");
        assertThat(profiles.get(agent.getId())).isSameAs(profile);

        assertThat(created.apiKey()).startsWith(AgentCredentials.API_KEY_PREFIX);
        assertThat(created.webhookSecret()).startsWith(AgentCredentials.WEBHOOK_SECRET_PREFIX);

        // api_key：库里只有哈希，而且 hash(明文) 必须与它相等（否则 Agent 永远连不上）
        assertThat(secrets.get(agent.getId(), SecretType.API_KEY_HASH))
                .isEqualTo(Digests.sha256Hex(created.apiKey()));
        assertThat(secrets.get(agent.getId(), SecretType.API_KEY_HASH))
                .as("库里不能出现明文 api_key")
                .isNotEqualTo(created.apiKey());

        // webhook_secret：必须是明文（HMAC 的密钥不能是哈希）
        assertThat(secrets.get(agent.getId(), SecretType.WEBHOOK_SECRET))
                .as("验签要算 HMAC，所以这一行必须能读回明文")
                .isEqualTo(created.webhookSecret());
    }

    @Test
    @DisplayName("创建：非 WEBHOOK 模式不发 webhook_secret（给一个永远不会被调用的密钥会误导对方）")
    void webhookSecretOnlyForWebhookMode() {
        AgentService.Created created = service.create(ALICE,
                new AgentService.CreateCommand("pull_bot", null, null, PushMode.PULL, null, null, null, null));

        assertThat(created.webhookSecret()).isNull();
        assertThat(secrets.get(created.actor().getId(), SecretType.WEBHOOK_SECRET)).isNull();
        assertThat(created.profile().getRateLimit())
                .as("未指定时取 tm.agent.default-rate-limit-per-minute")
                .isEqualTo(properties.getDefaultRateLimitPerMinute());
    }

    @Test
    @DisplayName("创建：handle 走与人类同一套规则（大写归一化、非法 40004、重复 40005）")
    void handleFollowsTheSameRulesAsHumans() {
        AgentService.Created created = service.create(ALICE, command("Weather_Bot", PushMode.PULL));
        assertThat(created.actor().getHandle()).as("与注册人类账号同一条归一化").isEqualTo("weather_bot");

        assertThat(codeOf(() -> service.create(ALICE, command("weather_bot", PushMode.PULL))))
                .isEqualTo(ErrorCode.HANDLE_EXISTS);
        assertThat(codeOf(() -> service.create(ALICE, command("ab", PushMode.PULL))))
                .as("少于 3 位")
                .isEqualTo(ErrorCode.INVALID_HANDLE);
        assertThat(codeOf(() -> service.create(ALICE, command("bad handle", PushMode.PULL))))
                .isEqualTo(ErrorCode.INVALID_HANDLE);
    }

    @Test
    @DisplayName("创建：push_mode 缺失 40001；WEBHOOK 没有 endpoint 40001；地址不是 http(s) 40002")
    void pushModeAndEndpointCombinationIsChecked() {
        assertThat(codeOf(() -> service.create(ALICE,
                new AgentService.CreateCommand("no_mode", null, null, null, null, null, null, null))))
                .isEqualTo(ErrorCode.MISSING_PARAMETER);

        assertThat(codeOf(() -> service.create(ALICE,
                new AgentService.CreateCommand("no_hook", null, null, PushMode.WEBHOOK, null, null, null, null))))
                .as("WEBHOOK 而没有地址，等于把推送丢进黑洞")
                .isEqualTo(ErrorCode.MISSING_PARAMETER);

        assertThat(codeOf(() -> service.create(ALICE,
                new AgentService.CreateCommand("bad_hook", null, null, PushMode.WEBHOOK,
                        "ftp://x/y", null, null, null))))
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("创建：拥有者必须存在且未停用（否则创建出一个没人负责的身份）")
    void ownerMustExistAndBeActive() {
        assertThat(codeOf(() -> service.create(999999L, command("orphan", PushMode.PULL))))
                .isEqualTo(ErrorCode.ACTOR_NOT_FOUND);

        Actor bob = actors.put(BOB, "bob");
        bob.setStatus(ActorStatus.SUSPENDED);
        assertThat(codeOf(() -> service.create(BOB, command("banned_owner", PushMode.PULL))))
                .as("封禁之后换个马甲继续用是不允许的")
                .isEqualTo(ErrorCode.ACCOUNT_SUSPENDED);
    }

    @Test
    @DisplayName("创建：capabilities 归一化（小写、去重、去空白），存成 JSON 数组文本")
    void capabilitiesAreNormalized() {
        AgentService.Created created = service.create(ALICE,
                new AgentService.CreateCommand("cap_bot", null, null, PushMode.PULL, null,
                        List.of("Text", " image ", "TEXT", ""), null, null));

        String json = created.profile().getCapabilities();
        assertThat(Json.readList(json, String.class)).containsExactly("text", "image");
    }

    @Test
    @DisplayName("创建：一个账号最多 50 个 Agent（防批量刷资源）")
    void ownerAgentLimit() {
        properties.setDefaultRateLimitPerMinute(60);
        for (int i = 0; i < 50; i++) {
            service.create(ALICE, new AgentService.CreateCommand("bot_" + i, null, null,
                    PushMode.PULL, null, null, null, null));
        }
        assertThat(codeOf(() -> service.create(ALICE, command("one_more", PushMode.PULL))))
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
    }

    // ================================================================ 查询与修改

    @Test
    @DisplayName("列表只包含自己创建的；详情/修改/轮换/停用都要求拥有者（40302）")
    void everythingRequiresOwnership() {
        Actor mine = service.create(ALICE, command("mine", PushMode.WEBHOOK)).actor();
        Actor theirs = service.create(BOB,
                new AgentService.CreateCommand("theirs", null, null, PushMode.PULL, null, null, null, null))
                .actor();

        assertThat(service.list(ALICE)).hasSize(1);
        assertThat(service.list(ALICE).get(0).actor().getId()).isEqualTo(mine.getId());

        assertThat(codeOf(() -> service.detail(BOB, mine.getId()))).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(codeOf(() -> service.update(BOB, mine.getId(), new AgentService.Patch("x", null, null, null, null, null, null))))
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(codeOf(() -> service.rotateKey(BOB, mine.getId()))).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(codeOf(() -> service.disable(BOB, mine.getId()))).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(codeOf(() -> service.detail(ALICE, theirs.getId()))).isEqualTo(ErrorCode.PERMISSION_DENIED);

        // 不是 Agent 的 actor（人类）走同一个码：它没有 profile
        assertThat(codeOf(() -> service.detail(ALICE, BOB))).isEqualTo(ErrorCode.ACTOR_NOT_FOUND);
    }

    @Test
    @DisplayName("PATCH：null 表示不改；只改 push_mode 时用已有的 endpoint 做组合校验")
    void patchIsPartialAndValidatesTheCombination() {
        Actor agent = service.create(ALICE, new AgentService.CreateCommand("patch_bot", null, null,
                PushMode.WS, null, null, null, null)).actor();

        // 只改昵称：其余字段不动
        AgentService.AgentEntry updated = service.update(ALICE, agent.getId(),
                new AgentService.Patch("新名字", null, null, null, null, null, null));
        assertThat(updated.actor().getDisplayName()).isEqualTo("新名字");
        assertThat(updated.profile().getPushMode()).as("未提供的字段不改").isEqualTo(PushMode.WS);

        // 切成 WEBHOOK 而不给地址：已有配置里也没有 → 40001
        assertThat(codeOf(() -> service.update(ALICE, agent.getId(),
                new AgentService.Patch(null, null, PushMode.WEBHOOK, null, null, null, null))))
                .isEqualTo(ErrorCode.MISSING_PARAMETER);

        // 先给地址，再切模式：两次都合法
        service.update(ALICE, agent.getId(),
                new AgentService.Patch(null, null, null, "https://hook.example.com/x", null, null, null));
        AgentService.AgentEntry webhooked = service.update(ALICE, agent.getId(),
                new AgentService.Patch(null, null, PushMode.WEBHOOK, null, null, null, null));
        assertThat(webhooked.profile().getPushMode()).isEqualTo(PushMode.WEBHOOK);
    }

    @Test
    @DisplayName("rate_limit 必须是正整数（想停用请用 DELETE，而不是把它设成 0）")
    void rateLimitMustBePositive() {
        Actor agent = service.create(ALICE, command("rate_bot", PushMode.PULL)).actor();
        assertThat(codeOf(() -> service.update(ALICE, agent.getId(),
                new AgentService.Patch(null, null, null, null, null, null, 0))))
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
    }

    // ================================================================ 轮换与停用

    @Test
    @DisplayName("轮换 api_key：换了一把新的，旧的哈希被覆盖（旧 key 立即失效）")
    void rotateKeyReplacesTheHash() {
        AgentService.Created created = service.create(ALICE, command("rotate_bot", PushMode.PULL));
        long actorId = created.actor().getId();
        String oldHash = secrets.get(actorId, SecretType.API_KEY_HASH);

        String rotated = service.rotateKey(ALICE, actorId);

        assertThat(rotated).isNotEqualTo(created.apiKey());
        assertThat(secrets.get(actorId, SecretType.API_KEY_HASH))
                .isEqualTo(Digests.sha256Hex(rotated))
                .isNotEqualTo(oldHash);
        assertThat(secrets.findActorIdByHash(SecretType.API_KEY_HASH, oldHash))
                .as("旧 key 的哈希不再指向任何人 —— 这就是「立即失效」")
                .isEmpty();
    }

    @Test
    @DisplayName("停用：status=SUSPENDED、webhook_secret 删掉、api_key 哈希**保留**（以便回 40301）")
    void disableKeepsApiKeyHashOnPurpose() {
        AgentService.Created created = service.create(ALICE, command("dead_bot", PushMode.WEBHOOK));
        long actorId = created.actor().getId();
        String hash = secrets.get(actorId, SecretType.API_KEY_HASH);

        AgentService.AgentEntry disabled = service.disable(ALICE, actorId);

        assertThat(disabled.actor().getStatus()).isEqualTo(ActorStatus.SUSPENDED);
        assertThat(secrets.get(actorId, SecretType.WEBHOOK_SECRET))
                .as("停用后不该再签出任何回调")
                .isNull();
        assertThat(secrets.get(actorId, SecretType.API_KEY_HASH))
                .as("保留哈希：鉴权要走能到「账号已停用」那一步，回 40301 而不是 40105")
                .isEqualTo(hash);
        assertThat(secrets.findActorIdByHash(SecretType.API_KEY_HASH, hash)).contains(actorId);

        // 再次停用是幂等的
        assertThat(service.disable(ALICE, actorId).actor().getStatus()).isEqualTo(ActorStatus.SUSPENDED);
        // 停用后不能轮换密钥（否则等于「先停用再换把钥匙继续用」）
        assertThat(codeOf(() -> service.rotateKey(ALICE, actorId)))
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
    }

    // ================================================================ 替身

    private static AgentService.CreateCommand command(String handle, PushMode mode) {
        return new AgentService.CreateCommand(handle, null, null, mode,
                mode == PushMode.WEBHOOK ? "https://agent.example.com/hook" : null,
                null, null, null);
    }

    private static ErrorCode codeOf(Runnable action) {
        try {
            action.run();
            throw new AssertionError("期望抛 TmException，实际成功");
        } catch (TmException e) {
            return e.errorCode();
        }
    }

    /** 只够本测试用的凭据仓储：按 (actorId, type) 存一行。 */
    private static final class MapSecrets implements ActorSecretRepository {

        private final Map<String, String> byKey = new LinkedHashMap<>();

        @Override
        public Optional<ActorSecret> find(long actorId, SecretType secretType) {
            String value = byKey.get(key(actorId, secretType));
            return value == null ? Optional.empty() : Optional.of(row(actorId, secretType, value));
        }

        @Override
        public Optional<Long> findActorIdByHash(SecretType secretType, String secretHash) {
            return byKey.entrySet().stream()
                    .filter(e -> e.getKey().endsWith(":" + secretType.code()))
                    .filter(e -> e.getValue().equals(secretHash))
                    .map(e -> Long.parseLong(e.getKey().substring(0, e.getKey().indexOf(':'))))
                    .findFirst();
        }

        @Override
        public void upsert(ActorSecret secret) {
            byKey.put(key(secret.getActorId(), secret.getSecretType()), secret.getSecretHash());
        }

        @Override
        public boolean delete(long actorId, SecretType secretType) {
            return byKey.remove(key(actorId, secretType)) != null;
        }

        String get(long actorId, SecretType type) {
            return byKey.get(key(actorId, type));
        }

        private static String key(long actorId, SecretType type) {
            return actorId + ":" + type.code();
        }

        private static ActorSecret row(long actorId, SecretType type, String value) {
            ActorSecret secret = new ActorSecret();
            secret.setActorId(actorId);
            secret.setSecretType(type);
            secret.setSecretHash(value);
            return secret;
        }
    }

    /** 递增 id，让断言能对着具体 id 说话。 */
    private static final class Sequential implements IdGenerator {

        private long next = 200_000_000_000_000_000L;

        @Override
        public long nextId() {
            return ++next;
        }

        @Override
        public int nodeId() {
            return 1;
        }
    }
}
