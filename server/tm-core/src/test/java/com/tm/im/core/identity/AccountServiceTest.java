package com.tm.im.core.identity;

import com.tm.im.common.crypto.PasswordHashes;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.core.config.IdentityProperties;
import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.enums.ActorStatus;
import com.tm.im.domain.enums.ActorType;
import com.tm.im.domain.enums.SecretType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 注册 / 登录 / 刷新 / 登出 的规则穷举（02-auth.md §2）。
 *
 * <p>全部跑在内存替身上。这样做的价值与代价都很清楚：<b>规则</b>（哪个错误码、
 * 有没有副作用）能被穷举；而<b>存储上的后果</b>（唯一索引真的拦住了并发注册、
 * refresh_token 真的被 GETDEL 掉）必须由真实 MySQL / Redis 的集成测试覆盖。
 * 两者出的错完全不同，谁也不能替代谁。
 *
 * <p><b>关于耗时</b>：注册/登录会真的跑 PBKDF2（默认 21 万次迭代），
 * 单个用例 100~300ms，整个类几秒钟。这里刻意不为「跑得快」把迭代数抽成配置项：
 * 那会多出一个能被误配成 1000 次的安全参数，而它的收益只是测试快几秒。
 */
class AccountServiceTest {

    private static final String JWT_SECRET = "0123456789abcdef0123456789abcdef"; // 32 字节
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String DEVICE = "web-chrome-131";

    private InMemoryActorRepository actors;
    private InMemoryActorSecretRepository secrets;
    private InMemoryRefreshTokenStore refreshTokens;
    private IdentityService identity;
    private IdentityProperties properties;
    private AccountService accounts;

    @BeforeEach
    void setUp() {
        actors = new InMemoryActorRepository();
        secrets = new InMemoryActorSecretRepository();
        refreshTokens = new InMemoryRefreshTokenStore();
        identity = new IdentityService(actors, secrets, new JwtTokenService(JWT_SECRET));
        properties = new IdentityProperties();
        properties.setJwtSecret(JWT_SECRET);
        properties.setAccessTokenTtl(Duration.ofHours(2));
        properties.setRefreshTokenTtl(Duration.ofDays(30));

        accounts = new AccountService(actors, secrets, refreshTokens, identity,
                new CountingIdGenerator(), properties, ZONE);
    }

    // ---------------------------------------------------------------- 注册

    @Test
    @DisplayName("注册：人类型、ACTIVE、handle 归一化为小写、口令只存哈希")
    void registerCreatesHumanActorWithHashedPassword() {
        AccountService.TokenPair pair = accounts.register(
                new AccountService.RegisterCommand("Alice", "s3cret-pass", "Alice Wang", DEVICE));

        Actor stored = actors.findById(pair.actorId()).orElseThrow();
        assertThat(stored.getActorType()).isEqualTo(ActorType.HUMAN);
        assertThat(stored.getStatus()).isEqualTo(ActorStatus.ACTIVE);
        // 归一化：库里 uk_handle 在 _ai_ci 下大小写不敏感，不归一化就会出现
        // 「existsHandle 说没占用、INSERT 却撞唯一键」
        assertThat(stored.getHandle()).isEqualTo("alice");
        assertThat(stored.getDisplayName()).isEqualTo("Alice Wang");
        assertThat(stored.getCreatedAt()).isNotNull();

        String hash = secrets.find(pair.actorId(), SecretType.PASSWORD_HASH).orElseThrow()
                .getSecretHash();
        assertThat(hash).isNotEqualTo("s3cret-pass").startsWith(PasswordHashes.ALGORITHM);
        assertThat(PasswordHashes.verify("s3cret-pass", hash)).isTrue();

        // 返回值里不能出现口令或其哈希
        assertThat(pair.accessToken()).doesNotContain("s3cret-pass");
        assertThat(pair.refreshToken()).startsWith("rt_");
    }

    @Test
    @DisplayName("注册：display_name 缺省取 handle（否则会话列表里是一片空白）")
    void registerDefaultsDisplayNameToHandle() {
        AccountService.TokenPair pair = accounts.register(
                new AccountService.RegisterCommand("bob", "s3cret-pass", "   ", null));

        assertThat(actors.findById(pair.actorId()).orElseThrow().getDisplayName()).isEqualTo("bob");
    }

    @Test
    @DisplayName("注册：handle 已被占用 → 40005，且大小写不同也算占用")
    void registerRejectsDuplicateHandle() {
        accounts.register(new AccountService.RegisterCommand("alice", "s3cret-pass", null, null));

        assertThatThrownBy(() -> accounts.register(
                new AccountService.RegisterCommand("alice", "another-pass", null, null)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.HANDLE_EXISTS);

        assertThatThrownBy(() -> accounts.register(
                new AccountService.RegisterCommand("ALICE", "another-pass", null, null)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.HANDLE_EXISTS);
    }

    @Test
    @DisplayName("注册：并发窗口输了（INSERT 撞唯一键）也回 40005，且不留半个账号")
    void registerTranslatesUniqueKeyViolationToHandleExists() {
        actors.failNextInsertWithDuplicateKey();

        assertThatThrownBy(() -> accounts.register(
                new AccountService.RegisterCommand("alice", "s3cret-pass", null, null)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.HANDLE_EXISTS);

        // 竞态失败必须是「什么都没发生」：既没有 actor 行，也没有凭据行
        assertThat(actors.findByHandle("alice")).isEmpty();
        assertThat(refreshTokens.issuedCount()).isZero();
    }

    @ParameterizedTest(name = "handle={0} 非法 → 40004")
    @ValueSource(strings = {"ab", "a", "@alice", "al ice", "al-ice", "al.ice", "alice!",
            "中文handle", "alice;drop"})
    void registerRejectsMalformedHandle(String handle) {
        assertThatThrownBy(() -> accounts.register(
                new AccountService.RegisterCommand(handle, "s3cret-pass", null, null)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_HANDLE);
    }

    @Test
    @DisplayName("注册：handle 超过 32 位 → 40004；正好 32 位则通过")
    void registerChecksHandleLengthBoundary() {
        String exactly32 = "a".repeat(32);
        assertThatCode(() -> accounts.register(
                new AccountService.RegisterCommand(exactly32, "s3cret-pass", null, null)))
                .doesNotThrowAnyException();

        String tooLong = "a".repeat(33);
        assertThatThrownBy(() -> accounts.register(
                new AccountService.RegisterCommand(tooLong, "s3cret-pass", null, null)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_HANDLE);
    }

    @Test
    @DisplayName("注册：handle 前后空白被去掉（复制粘贴的常态）")
    void registerTrimsHandleWhitespace() {
        AccountService.TokenPair pair = accounts.register(
                new AccountService.RegisterCommand("  carol  ", "s3cret-pass", null, null));

        assertThat(actors.findById(pair.actorId()).orElseThrow().getHandle()).isEqualTo("carol");
    }

    @Test
    @DisplayName("注册：handle 缺失/空 → 40001")
    void registerRequiresHandle() {
        assertThatThrownBy(() -> accounts.register(
                new AccountService.RegisterCommand(null, "s3cret-pass", null, null)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.MISSING_PARAMETER);
        assertThatThrownBy(() -> accounts.register(
                new AccountService.RegisterCommand("   ", "s3cret-pass", null, null)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.MISSING_PARAMETER);
    }

    @Test
    @DisplayName("注册：口令长度边界 8 / 128，越界 40002，缺失 40001")
    void registerChecksPasswordBounds() {
        assertThatCode(() -> accounts.register(
                new AccountService.RegisterCommand("user8", "12345678", null, null)))
                .doesNotThrowAnyException();
        assertThatCode(() -> accounts.register(
                new AccountService.RegisterCommand("user128", "x".repeat(128), null, null)))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> accounts.register(
                new AccountService.RegisterCommand("short", "1234567", null, null)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
        // 上限防的是「用 1MB 口令把 PBKDF2 变成 DoS」
        assertThatThrownBy(() -> accounts.register(
                new AccountService.RegisterCommand("toolong", "x".repeat(129), null, null)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
        assertThatThrownBy(() -> accounts.register(
                new AccountService.RegisterCommand("nopw", null, null, null)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.MISSING_PARAMETER);
    }

    @Test
    @DisplayName("注册：display_name 超过 128 字符 → 40002")
    void registerChecksDisplayNameLength() {
        assertThatThrownBy(() -> accounts.register(new AccountService.RegisterCommand(
                "dave", "s3cret-pass", "名".repeat(129), null)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
    }

    // ---------------------------------------------------------------- 登录

    @Test
    @DisplayName("登录：成功，且签出的 access token 能被验签并带回身份")
    void loginIssuesVerifiableToken() {
        AccountService.TokenPair registered = accounts.register(
                new AccountService.RegisterCommand("alice", "s3cret-pass", "Alice", DEVICE));

        AccountService.TokenPair pair = accounts.login(
                new AccountService.LoginCommand("alice", "s3cret-pass", DEVICE));

        assertThat(pair.actorId()).isEqualTo(registered.actorId());
        assertThat(pair.handle()).isEqualTo("alice");
        assertThat(pair.expiresInSeconds()).isEqualTo(Duration.ofHours(2).toSeconds());

        TokenClaims claims = new JwtTokenService(JWT_SECRET).verify(pair.accessToken());
        assertThat(claims.actorId()).isEqualTo(registered.actorId());
        assertThat(claims.handle()).isEqualTo("alice");
        assertThat(claims.actorType()).isEqualTo(ActorType.HUMAN);
    }

    @Test
    @DisplayName("登录：handle 大小写不敏感（归一化后才查）")
    void loginIsCaseInsensitiveOnHandle() {
        accounts.register(new AccountService.RegisterCommand("alice", "s3cret-pass", null, null));

        assertThat(accounts.login(new AccountService.LoginCommand("ALICE", "s3cret-pass", null))
                .handle()).isEqualTo("alice");
    }

    @Test
    @DisplayName("登录失败（口令错 / 账号不存在 / 无口令）一律 40101，且无法据此区分")
    void loginFailuresAreIndistinguishable() {
        accounts.register(new AccountService.RegisterCommand("alice", "s3cret-pass", null, null));
        // 造一个「没有口令」的账号（Agent 就是这种），它也不该能被密码登录
        Actor agent = new Actor();
        agent.setId(999L);
        agent.setActorType(ActorType.AGENT);
        agent.setHandle("weather_bot");
        agent.setDisplayName("天气");
        agent.setStatus(ActorStatus.ACTIVE);
        actors.insert(agent);

        assertThatThrownBy(() -> accounts.login(
                new AccountService.LoginCommand("alice", "wrong-pass", null)))
                .extracting(e -> ((TmException) e).errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThatThrownBy(() -> accounts.login(
                new AccountService.LoginCommand("nobody", "s3cret-pass", null)))
                .extracting(e -> ((TmException) e).errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThatThrownBy(() -> accounts.login(
                new AccountService.LoginCommand("weather_bot", "s3cret-pass", null)))
                .extracting(e -> ((TmException) e).errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("登录失败的错误文案里不含口令，且 detail 不外带")
    void loginFailureNeverEchoesPassword() {
        accounts.register(new AccountService.RegisterCommand("alice", "s3cret-pass", null, null));

        TmException e = (TmException) org.assertj.core.api.Assertions.catchThrowable(
                () -> accounts.login(new AccountService.LoginCommand("alice", "hunter2-secret", null)));

        assertThat(e.getMessage()).doesNotContain("hunter2-secret");
        assertThat(e.detail()).isEqualTo("handle=alice");
    }

    @Test
    @DisplayName("登录：账号被停用 → 40301，而不是 40101")
    void loginRejectsSuspendedAccount() {
        AccountService.TokenPair pair = accounts.register(
                new AccountService.RegisterCommand("alice", "s3cret-pass", null, null));
        actors.findById(pair.actorId()).orElseThrow().setStatus(ActorStatus.SUSPENDED);

        assertThatThrownBy(() -> accounts.login(
                new AccountService.LoginCommand("alice", "s3cret-pass", null)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.ACCOUNT_SUSPENDED);
    }

    @Test
    @DisplayName("登录成功时顺手升级旧参数的密码哈希（唯一能做这件事的时机）")
    void loginUpgradesOutdatedPasswordHash() {
        Actor actor = new Actor();
        actor.setId(4242L);
        actor.setActorType(ActorType.HUMAN);
        actor.setHandle("legacy");
        actor.setDisplayName("legacy");
        actor.setStatus(ActorStatus.ACTIVE);
        actors.insert(actor);
        // 模拟一年前用 5 万次迭代存的哈希
        String oldHash = PasswordHashes.hash("old-pass-123", 50_000);
        var secret = new com.tm.im.domain.entity.ActorSecret();
        secret.setActorId(actor.getId());
        secret.setSecretType(SecretType.PASSWORD_HASH);
        secret.setSecretHash(oldHash);
        secrets.upsert(secret);

        accounts.login(new AccountService.LoginCommand("legacy", "old-pass-123", null));

        String now = secrets.find(actor.getId(), SecretType.PASSWORD_HASH).orElseThrow()
                .getSecretHash();
        assertThat(now).isNotEqualTo(oldHash);
        assertThat(PasswordHashes.iterationsOf(now)).isEqualTo(PasswordHashes.DEFAULT_ITERATIONS);
        // 升级之后旧口令仍然可用（哈希换了，口令没换）
        assertThat(PasswordHashes.verify("old-pass-123", now)).isTrue();
    }

    // ---------------------------------------------------------------- 刷新

    @Test
    @DisplayName("刷新：换出一对新令牌，旧的立即失效（一次性）")
    void refreshRotatesAndInvalidatesTheOldToken() {
        AccountService.TokenPair first = accounts.register(
                new AccountService.RegisterCommand("alice", "s3cret-pass", null, DEVICE));

        AccountService.TokenPair second = accounts.refresh(first.refreshToken());

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(second.actorId()).isEqualTo(first.actorId());
        // 旧 token 已经从存储里消失 → 再用就是 40104
        assertThatThrownBy(() -> accounts.refresh(first.refreshToken()))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
        // 而新 token 可用
        assertThat(accounts.refresh(second.refreshToken()).actorId()).isEqualTo(first.actorId());
    }

    @Test
    @DisplayName("刷新的设备标识来自会话，不能由请求改写")
    void refreshKeepsDeviceFromSession() {
        AccountService.TokenPair first = accounts.register(
                new AccountService.RegisterCommand("alice", "s3cret-pass", null, "tablet-ios-17"));
        AccountService.TokenPair second = accounts.refresh(first.refreshToken());

        // 存储里新会话的设备标识仍是登录时那个
        assertThat(refreshTokens.peek(second.refreshToken()).orElseThrow().deviceId())
                .isEqualTo("tablet-ios-17");
    }

    @Test
    @DisplayName("刷新：凭证不存在 / 空 / null 都是 40104")
    void refreshRejectsUnknownTokens() {
        for (String token : new String[]{"rt_unknown", "", null}) {
            assertThatThrownBy(() -> accounts.refresh(token))
                    .isInstanceOf(TmException.class)
                    .extracting(e -> ((TmException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
        }
    }

    @Test
    @DisplayName("刷新时账号已被停用 → 40301：封禁不能靠 refresh_token 绕过去")
    void refreshRejectsSuspendedAccount() {
        AccountService.TokenPair pair = accounts.register(
                new AccountService.RegisterCommand("alice", "s3cret-pass", null, DEVICE));
        actors.findById(pair.actorId()).orElseThrow().setStatus(ActorStatus.SUSPENDED);

        // 若不在这条路径上查状态，封禁的最坏生效延迟就从 2 小时变成 30 天
        assertThatThrownBy(() -> accounts.refresh(pair.refreshToken()))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.ACCOUNT_SUSPENDED);
    }

    @Test
    @DisplayName("刷新时账号已被删除 → 40401（token 合法但账号没了）")
    void refreshRejectsDeletedAccount() {
        // 直接造一个指向不存在 actor 的会话，比「注册后把行删掉」更接近真实成因：
        // 账号注销与 refresh_token 过期是两个独立的时间点。
        InMemoryRefreshTokenStore store = new InMemoryRefreshTokenStore();
        String orphan = store.issue(8888L, DEVICE, Duration.ofDays(30));
        AccountService service = new AccountService(actors, secrets, store, identity,
                new CountingIdGenerator(), properties, ZONE);

        assertThatThrownBy(() -> service.refresh(orphan))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.ACTOR_NOT_FOUND);
    }

    // ---------------------------------------------------------------- 登出

    @Test
    @DisplayName("登出：凭证立刻不可用；重复登出不报错（幂等）")
    void logoutRevokesAndIsIdempotent() {
        AccountService.TokenPair pair = accounts.register(
                new AccountService.RegisterCommand("alice", "s3cret-pass", null, DEVICE));

        accounts.logout(pair.refreshToken());
        assertThat(refreshTokens.contains(pair.refreshToken())).isFalse();
        assertThatThrownBy(() -> accounts.refresh(pair.refreshToken()))
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);

        // 第二次登出（弱网重试的常态）必须成功
        assertThatCode(() -> accounts.logout(pair.refreshToken())).doesNotThrowAnyException();
        assertThatCode(() -> accounts.logout(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refresh_token 的 TTL 用的是配置值（30 天），不是某个写死的数")
    void refreshTokenTtlComesFromConfiguration() {
        properties.setRefreshTokenTtl(Duration.ofDays(7));
        AccountService custom = new AccountService(actors, secrets, refreshTokens, identity,
                new CountingIdGenerator(), properties, ZONE);

        custom.register(new AccountService.RegisterCommand("alice", "s3cret-pass", null, DEVICE));

        assertThat(refreshTokens.lastTtl).isEqualTo(Duration.ofDays(7));
    }

    @Test
    @DisplayName("access token 的 TTL 用配置值，并原样反映在 expires_in 上")
    void accessTokenTtlReflectsConfiguration() {
        properties.setAccessTokenTtl(Duration.ofMinutes(30));
        AccountService custom = new AccountService(actors, secrets, refreshTokens, identity,
                new CountingIdGenerator(), properties, ZONE);

        AccountService.TokenPair pair = custom.register(
                new AccountService.RegisterCommand("alice", "s3cret-pass", null, DEVICE));

        assertThat(pair.expiresInSeconds()).isEqualTo(1800);
    }

    @Test
    @DisplayName("同一账号多次登录会产生多个互不影响的会话")
    void multipleLoginsAreIndependentSessions() {
        accounts.register(new AccountService.RegisterCommand("alice", "s3cret-pass", null, "web"));
        AccountService.TokenPair web = accounts.login(
                new AccountService.LoginCommand("alice", "s3cret-pass", "web"));
        AccountService.TokenPair mobile = accounts.login(
                new AccountService.LoginCommand("alice", "s3cret-pass", "mobile"));

        // 在手机上登出不该影响 Web 端
        accounts.logout(mobile.refreshToken());
        assertThat(accounts.refresh(web.refreshToken()).handle()).isEqualTo("alice");
    }

    @Test
    @DisplayName("口令是 UTF-8 字节语义：非 ASCII 口令能注册也能登录")
    void nonAsciiPasswordWorks() {
        String password = "口令-🎉-12345678";
        AccountService.TokenPair pair = accounts.register(
                new AccountService.RegisterCommand("emoji_user", password, null, DEVICE));

        assertThat(new JwtTokenService(JWT_SECRET).verify(pair.accessToken()).actorId())
                .isEqualTo(pair.actorId());
        assertThat(accounts.login(
                new AccountService.LoginCommand("emoji_user", password, null)).actorId())
                .isEqualTo(pair.actorId());
    }
}
