package com.tm.im.core.identity;

import com.tm.im.common.crypto.PasswordHashes;
import com.tm.im.common.crypto.RefreshTokens;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.common.id.IdGenerator;
import com.tm.im.core.config.IdentityProperties;
import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.entity.ActorSecret;
import com.tm.im.domain.enums.ActorStatus;
import com.tm.im.domain.enums.ActorType;
import com.tm.im.domain.enums.SecretType;
import com.tm.im.domain.repository.ActorRepository;
import com.tm.im.domain.repository.ActorSecretRepository;
import com.tm.im.domain.repository.RefreshSession;
import com.tm.im.domain.repository.RefreshTokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 账号：注册、登录、刷新、登出（02-auth.md §2）。
 *
 * <p>本类是<b>人类</b>账号的入口。Agent 不走这里：它的凭证是 api_key，由
 * 「人类创建 Agent」时一次性下发（§3.1，属 M8）。这个边界不是产品取舍，而是
 * 事实：Agent 没有「口令」这个东西，所以它没有「登录」这个动作。
 * 注册出来的行 {@code actor_type} 恒为 {@link ActorType#HUMAN}。
 *
 * <p>但它里面的两条<b>静态</b>规则（{@link #normalizeHandle} /
 * {@link #normalizeDisplayName}）是公开的，因为 Agent 创建必须走同一套：
 * 「@alice 是合法 handle 而 Agent 的 @Bob_Bot 不是」这种事一旦出现，
 * 「对等」就只剩下口号了。共享的不是代码，是<b>规则</b>。
 *
 * <p>凡是「一条规则能写在两处」的地方，这里都只写一遍：
 * <ul>
 *   <li>handle 的合法性只在 {@link #HANDLE_PATTERN}；</li>
 *   <li>口令的长度区间只在 {@link #MIN_PASSWORD}/{@link #MAX_PASSWORD}；</li>
 *   <li>「账号还能不能用」只在 {@link IdentityService#requireActive}；</li>
 *   <li>令牌怎么签发只在 {@link #issueTokens}。</li>
 * </ul>
 * 这些规则一旦有两份，漂移方向总是「注册时严、登录时松」或反之，
 * 而两种漂移都表现为「明明按文档写的却登不上」。
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    /**
     * handle 规则（02-auth.md §2.1 / 07-errors-limits 40004）：3-32 位字母数字下划线。
     *
     * <p>{@code ^...$} 与 {@code find()} 的差别在这里是安全问题：用 {@code find()} 的话
     * {@code "alice; drop"} 也算匹配（因为含字母数字），而 handle 会被拼进
     * SQL 之外的展示、@ 提及解析、对象存储路径等地方。
     */
    static final Pattern HANDLE_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,32}$");

    static final int MIN_PASSWORD = 8;

    /**
     * 口令长度上限。存在的理由是拒绝服务而不是安全：PBKDF2 的成本与口令长度近似线性，
     * 一个 1MB 的「口令」能让单次登录消耗掉整台机器的 CPU。
     */
    static final int MAX_PASSWORD = 128;

    static final int MAX_DISPLAY_NAME = 128;

    /**
     * 库里 {@code actor.handle} 的唯一索引是 {@code uk_handle}，而目标实例的排序规则是
     * {@code utf8mb4_0900_ai_ci}（大小写不敏感，见 README「排序规则为什么必须显式写」）。
     * 于是 {@code Alice} 与 {@code alice} 在数据库看来是<b>同一个</b> handle。
     *
     * <p>若这里不做归一化，就只剩一条更差的路：{@code existsHandle("Alice")} 返回 false
     * （精确匹配查不到），随后 INSERT 撞唯一键抛出 40005 —— 用户看到一个
     * 与「这个 handle 被占用」一模一样的错误，而占用它的其实是他自己刚试过的
     * {@code alice}。所以统一小写再比对、再落库。
     */
    private static final Locale HANDLE_LOCALE = Locale.ROOT;

    private final ActorRepository actors;
    private final ActorSecretRepository secrets;
    private final RefreshTokenStore refreshTokens;
    private final IdentityService identity;
    private final IdGenerator idGenerator;
    private final IdentityProperties properties;
    private final ZoneId databaseZone;

    /**
     * 「不存在这个账号」时也要花掉的同等时间。
     *
     * <p>不这么做的话，登录接口的响应时间就能区分「handle 存在」与「不存在」
     * （前者要跑一次 PBKDF2，后者立刻返回），攻击者可以用它枚举出系统里有哪些账号。
     * 这类泄漏在本系统里格外敏感：Agent 的 handle 是公开可搜的（§2.4），
     * 但人类账号的存在性不应被批量探测。
     *
     * <p>延迟到首次使用才计算：{@link PasswordHashes#DEFAULT_ITERATIONS} 下的
     * 一次哈希是本机 100ms 量级，放在静态初始化里等于给每次启动都加一笔
     * 与功能无关的固定开销。
     */
    private volatile String timingEqualizer;

    public AccountService(ActorRepository actors,
                          ActorSecretRepository secrets,
                          RefreshTokenStore refreshTokens,
                          IdentityService identity,
                          IdGenerator idGenerator,
                          IdentityProperties properties,
                          ZoneId databaseZone) {
        this.actors = actors;
        this.secrets = secrets;
        this.refreshTokens = refreshTokens;
        this.identity = identity;
        this.idGenerator = idGenerator;
        this.properties = properties;
        this.databaseZone = databaseZone;
    }

    /** 注册入参。{@code deviceId} 只存进 refresh 会话，不参与鉴权（见 {@link RefreshSession}）。 */
    public record RegisterCommand(String handle, String password, String displayName, String deviceId) {
    }

    public record LoginCommand(String handle, String password, String deviceId) {
    }

    /**
     * 一次登录/注册/刷新的结果。
     *
     * <p>{@code actorId} 与 {@code handle} 一并返回，是因为注册之后客户端必然
     * 立刻要显示「我是谁」；让它再发一次 {@code GET /v1/me} 只是多一次往返。
     * 02-auth.md §2.1 的响应体里没有这两个字段——多返回字段对客户端的兼容性
     * 影响是零（文档 §1 明确「忽略未知字段」是协议演进的前提），而少返回一个
     * 客户端要用的字段就没法补救。
     */
    public record TokenPair(long actorId, String handle, String accessToken, String refreshToken,
                            long expiresInSeconds) {
    }

    @Transactional
    public TokenPair register(RegisterCommand cmd) {
        String handle = normalizeHandle(cmd.handle());
        String password = requirePassword(cmd.password());
        String displayName = normalizeDisplayName(cmd.displayName(), handle);

        // 先查一次是「为了给出好错误」，不是「为了保证唯一」：
        // 两个并发注册之间仍有窗口，真正的唯一性由 uk_handle 保证（下面 catch）。
        if (actors.existsHandle(handle)) {
            throw new TmException(ErrorCode.HANDLE_EXISTS, "handle=" + handle);
        }

        Actor actor = new Actor();
        actor.setId(idGenerator.nextId());
        actor.setActorType(ActorType.HUMAN);
        actor.setHandle(handle);
        actor.setDisplayName(displayName);
        actor.setStatus(ActorStatus.ACTIVE);
        // createdAt 的含义是「库里 DATETIME 表示的墙上时间」，口径必须与
        // MessageService 落库时用的同一个时区（tm.time.zone）。
        actor.setCreatedAt(LocalDateTime.now(databaseZone));

        try {
            actors.insert(actor);
        } catch (DuplicateKeyException e) {
            // 竞态输了。对外与「预先查到已存在」完全一致（40005），
            // 否则攻击者能用响应差异判断自己的请求是不是撞上了竞态窗口。
            throw new TmException(ErrorCode.HANDLE_EXISTS, "handle=" + handle);
        }

        ActorSecret secret = new ActorSecret();
        secret.setActorId(actor.getId());
        secret.setSecretType(SecretType.PASSWORD_HASH);
        secret.setSecretHash(PasswordHashes.hash(password));
        secrets.upsert(secret);

        log.info("注册成功 actorId={} handle={}", actor.getId(), handle);
        return issueTokens(actor, cmd.deviceId());
    }

    public TokenPair login(LoginCommand cmd) {
        String handle = normalizeHandle(cmd.handle());
        Optional<Actor> found = actors.findByHandle(handle);

        if (found.isEmpty()) {
            burnSameTimeAsVerification(cmd.password());
            // 与「口令错误」同一个错误码、同一句话（见下面对失败的说明）。
            throw new TmException(ErrorCode.UNAUTHORIZED, "handle=" + handle);
        }
        Actor actor = found.get();

        Optional<ActorSecret> stored = secrets.find(actor.getId(), SecretType.PASSWORD_HASH);
        if (stored.isEmpty()) {
            // 账号存在但没有口令（Agent 账号就是这种）。走同一条失败路径：
            // 「这个 handle 不能用密码登录」与「密码错了」对外不能有区别，
            // 否则可以据此枚举出哪些 handle 属于 Agent。
            burnSameTimeAsVerification(cmd.password());
            throw new TmException(ErrorCode.UNAUTHORIZED, "handle=" + handle);
        }

        String password = requirePassword(cmd.password());
        if (!PasswordHashes.verify(password, stored.get().getSecretHash())) {
            // 错误文案刻意不区分「账号不存在」与「口令错误」：区分开就等于
            // 提供了一个 handle 存在性探测器（02-auth.md 对刷新失败也是同一口径）。
            log.info("登录失败 handle={}", handle);
            throw new TmException(ErrorCode.UNAUTHORIZED, "handle=" + handle);
        }

        Actor active = identity.requireActive(actor.getId());
        rehashIfOutdated(actor.getId(), password, stored.get());
        log.info("登录成功 actorId={} handle={}", active.getId(), active.getHandle());
        return issueTokens(active, cmd.deviceId());
    }

    /**
     * 用 refresh_token 换一对新令牌（02-auth.md §2.3）。
     *
     * <p>{@code deviceId} 取自<b>会话</b>而不是请求：客户端可以自报任意值，
     * 若用它覆盖，一个被偷走的 refresh_token 就能顺手把设备标识改成自己的，
     * 让「看我的登录设备列表」这类后续能力失去意义。
     */
    public TokenPair refresh(String refreshToken) {
        RefreshSession session = refreshTokens.consume(refreshToken)
                .orElseThrow(() -> new TmException(ErrorCode.INVALID_REFRESH_TOKEN));

        // 封禁要在这里再生效一次：refresh_token 的有效期是 30 天，
        // 而 access token 只有 2 小时 —— 若不在刷新时查状态，一个被封禁的账号
        // 可以靠刷新一直待下去（「封禁的最坏生效延迟 = token 有效期」就变成了 30 天）。
        Actor actor = identity.requireActive(session.actorId());
        log.debug("刷新成功 actorId={} device={}", actor.getId(), session.deviceId());
        return issueTokens(actor, session.deviceId());
    }

    /**
     * 登出。幂等：凭证不存在或已过期也算成功（弱网重试是常态，
     * 第二次失败会让客户端把用户留在「看起来没退出」的状态里）。
     */
    public void logout(String refreshToken) {
        refreshTokens.revoke(refreshToken);
    }

    /**
     * 令牌签发唯一入口。access 与 refresh 总是成对产生：
     * 若某条路径只发 access，客户端会在 2 小时后发现自己无法续期；
     * 只发 refresh 则更糟——那个「成功」的响应里没有能用的凭证。
     */
    private TokenPair issueTokens(Actor actor, String deviceId) {
        String access = identity.tokens().issue(actor.getId(), actor.getHandle(),
                actor.getActorType(), properties.getAccessTokenTtl());
        String refresh = refreshTokens.issue(actor.getId(), deviceId, properties.getRefreshTokenTtl());
        return new TokenPair(actor.getId(), actor.getHandle(), access, refresh,
                properties.getAccessTokenTtl().toSeconds());
    }

    /**
     * 登录成功时顺手用当前参数重新哈希。
     *
     * <p>这是唯一能做这件事的时机：升级需要明文口令，而明文只在校验那一瞬间存在。
     * 失败只记日志不抛出——口令已经验证通过了，磁盘上多留一条旧参数的哈希
     * 远好于因为一次写失败而让用户登不进去。
     */
    private void rehashIfOutdated(long actorId, String password, ActorSecret stored) {
        if (!PasswordHashes.needsRehash(stored.getSecretHash())) {
            return;
        }
        try {
            ActorSecret upgraded = new ActorSecret();
            upgraded.setActorId(actorId);
            upgraded.setSecretType(SecretType.PASSWORD_HASH);
            upgraded.setSecretHash(PasswordHashes.hash(password));
            secrets.upsert(upgraded);
            log.info("密码哈希参数已升级 actorId={} {} → {} 次迭代",
                    actorId, PasswordHashes.iterationsOf(stored.getSecretHash()),
                    PasswordHashes.DEFAULT_ITERATIONS);
        } catch (RuntimeException e) {
            log.warn("密码哈希参数升级失败，保留旧哈希 actorId={}", actorId, e);
        }
    }

    /** 与 {@link PasswordHashes#verify} 等价的一次计算，结果丢弃（见 {@link #timingEqualizer}）。 */
    private void burnSameTimeAsVerification(String password) {
        String eq = timingEqualizer;
        if (eq == null) {
            eq = PasswordHashes.hash("tm-login-timing-equalizer");
            timingEqualizer = eq;
        }
        // 口令为 null 时 verify 会抛（那是调用方的 bug），这里用空串兜住：
        // 这条路径只为了消耗时间，不该因为入参不合法而改变失败的错误码。
        PasswordHashes.verify(password == null ? "" : password, eq);
    }

    /**
     * handle 归一化 + 校验。
     *
     * <p>先 {@code trim()} 再校验：用户复制粘贴时带上尾随空格是常态，
     * 而 {@code "alice "} 与 {@code "alice"} 在 {@code _ai_ci} 排序规则下
     * 是相等的（PAD SPACE）—— 不 trim 就会变成「注册时看着成功、登录时参数校验失败」。
     */
    public static String normalizeHandle(String handle) {
        if (handle == null) {
            throw new TmException(ErrorCode.MISSING_PARAMETER, "handle 缺失");
        }
        String trimmed = handle.strip();
        if (trimmed.isEmpty()) {
            throw new TmException(ErrorCode.MISSING_PARAMETER, "handle 为空");
        }
        if (!HANDLE_PATTERN.matcher(trimmed).matches()) {
            throw new TmException(ErrorCode.INVALID_HANDLE,
                    "handle 必须是 3-32 位字母数字下划线");
        }
        return trimmed.toLowerCase(HANDLE_LOCALE);
    }

    static String requirePassword(String password) {
        if (password == null || password.isEmpty()) {
            throw new TmException(ErrorCode.MISSING_PARAMETER, "password 缺失");
        }
        if (password.length() < MIN_PASSWORD) {
            throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "password 至少 " + MIN_PASSWORD + " 位");
        }
        if (password.length() > MAX_PASSWORD) {
            throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "password 最长 " + MAX_PASSWORD + " 位");
        }
        return password;
    }

    public static String normalizeDisplayName(String displayName, String handle) {
        String value = displayName == null ? "" : displayName.strip();
        if (value.isEmpty()) {
            // 默认取 handle：展示名为空会让客户端在会话列表里显示一片空白。
            return handle;
        }
        if (value.length() > MAX_DISPLAY_NAME) {
            throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "display_name 最长 " + MAX_DISPLAY_NAME + " 字符");
        }
        return value;
    }
}
