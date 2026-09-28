package com.tm.im.core.admin;

import com.tm.im.common.crypto.OpaqueToken;
import com.tm.im.common.crypto.PasswordHashes;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.common.id.IdGenerator;
import com.tm.im.common.json.Json;
import com.tm.im.core.conversation.PageCursors;
import com.tm.im.core.plaza.PostDeletionPort;
import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.entity.AdminAuditLog;
import com.tm.im.domain.entity.AdminSession;
import com.tm.im.domain.entity.AdminUser;
import com.tm.im.domain.entity.AgentProfile;
import com.tm.im.domain.entity.Post;
import com.tm.im.domain.enums.ActorStatus;
import com.tm.im.domain.enums.ActorType;
import com.tm.im.domain.enums.AdminRole;
import com.tm.im.domain.enums.AdminStatus;
import com.tm.im.domain.repository.ActorRepository;
import com.tm.im.domain.repository.AdminAuditLogRepository;
import com.tm.im.domain.repository.AdminSessionRepository;
import com.tm.im.domain.repository.AdminUserRepository;
import com.tm.im.domain.repository.AgentProfileRepository;
import com.tm.im.domain.repository.PostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 管理后台的领域服务（M9）。
 *
 * <p><b>这套接口最要紧的性质是「独立」</b>（DESIGN §2.4）：它不认 JWT、不认 api_key，
 * 只认自己的 {@code adm_} 会话；它也不住在 {@code tm-app} 里，而是与用户端
 * <b>两个 JAR、两个端口</b>。理由是漏洞面：用户端代码的每一处输入解析、
 * 每一个第三方依赖，都会出现在那个进程里——若管理接口与它们同进程，
 * 就等于把这些面也给了「能封任何人号」的能力。
 *
 * <p><b>后台不是「actor 的一种」</b>：管理员没有 handle、没有好友、不发消息，
 * 它不参与任何会话。这一点在库里表现为 {@code admin_user} 与 {@code actor} 是两张
 * 互不相干的表（见 DDL 注释）——因此本服务里也找不到「把管理员当成 Actor」的转换。
 *
 * <p><b>每一个写动作都写审计，且与业务变更同事务</b>（见 {@code AdminAuditLogRepository}）。
 * 审计不是「顺便记一笔」：它对后台的价值等同于消息的 seq——没有它，
 * 事后就只能靠「谁可能知道口令」来推断。
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    /** 后台会话凭证前缀。改动它等于让所有后台会话失效（那会让运维全部被登出）。 */
    public static final String SESSION_PREFIX = "adm_";

    /** 后台用户名：小写字母数字下划线，3-32 位（与 handle 形状一致，但**不是同一个命名空间**）。 */
    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9_]{3,32}$");

    // ---------------- 审计动作（写成常量：它们会进查询条件，拼错就查不到东西） ----------------
    public static final String ACTION_LOGIN = "ADMIN_LOGIN";
    public static final String ACTION_LOGIN_FAILED = "ADMIN_LOGIN_FAILED";
    public static final String ACTION_LOGOUT = "ADMIN_LOGOUT";
    public static final String ACTION_CREATE_ADMIN = "ADMIN_CREATE";
    public static final String ACTION_ADMIN_STATUS = "ADMIN_STATUS";
    public static final String ACTION_ACTOR_STATUS = "ACTOR_STATUS";
    public static final String ACTION_POST_DELETE = "POST_DELETE";

    public static final String TARGET_ACTOR = "ACTOR";
    public static final String TARGET_AGENT = "AGENT";
    public static final String TARGET_POST = "POST";
    public static final String TARGET_ADMIN = "ADMIN";

    private final AdminUserRepository admins;
    private final AdminSessionRepository sessions;
    private final AdminAuditLogRepository auditLogs;
    private final ActorRepository actors;
    private final AgentProfileRepository agentProfiles;
    private final PostRepository posts;
    private final PostDeletionPort postDeletion;
    private final IdGenerator idGenerator;
    private final AdminProperties properties;
    private final ZoneId databaseZone;

    public AdminService(AdminUserRepository admins,
                        AdminSessionRepository sessions,
                        AdminAuditLogRepository auditLogs,
                        ActorRepository actors,
                        AgentProfileRepository agentProfiles,
                        PostRepository posts,
                        PostDeletionPort postDeletion,
                        IdGenerator idGenerator,
                        AdminProperties properties,
                        ZoneId databaseZone) {
        this.admins = admins;
        this.sessions = sessions;
        this.auditLogs = auditLogs;
        this.actors = actors;
        this.agentProfiles = agentProfiles;
        this.posts = posts;
        this.postDeletion = postDeletion;
        this.idGenerator = idGenerator;
        this.properties = properties;
        this.databaseZone = databaseZone;
    }

    // ================================================================== 类型

    /** 分页结果：与用户端同一形状（{@code items} + {@code next_cursor} + {@code has_more}）。 */
    public record Page<T>(List<T> items, String nextCursor, boolean hasMore) {
    }

    /** 登录结果。{@code token} 是唯一一次能拿到会话明文的地方。 */
    public record LoginResult(String token, AdminUser admin, LocalDateTime expiresAt) {
    }

    /** 参与者详情：Actor 本体 + 可选的 Agent 扩展（人没有这一项）。 */
    public record ActorDetail(Actor actor, AgentProfile agentProfile) {
    }

    // ================================================================== 认证

    /**
     * 后台登录。
     *
     * <p><b>失败路径的措辞必须一致</b>：用户名不存在与口令不对都回 {@code 40101}，
     * 且 detail 都写「用户名或口令不正确」。区分它们会把登录接口变成用户名枚举器，
     * 而后台用户名往往是 {@code admin} 这类可猜值——枚举成功之后，
     * 攻击者剩下的工作就只有撞口令了。
     *
     * <p><b>「账号被停用」例外地提前返回 {@code 40301}</b>：那是已知的、需要运维知道的事实
     * （他被停用了，而不是把口令忘了）。用同一个 40101 会让人去翻口令本，
     * 而真正的原因是权限被收回。
     *
     * <p><b>锁定期间不再累计失败次数</b>：锁定已经生效，这段时间里的尝试被拒绝即可；
     * 若每次都写审计，攻击者就获得了「不花成本地往审计表写数据」的能力。
     *
     * <p><b>{@code noRollbackFor} 不是可选参数</b>：失败路径上的两处写入（失败计数、
     * 失败审计）<b>必须留下</b>，而本方法随后就要抛 {@code 40101}——Spring 默认对
     * 运行时异常回滚，于是那两处写入会一起消失，结果就是<b>锁定永远不会触发</b>，
     * 而表现完全正常（登录失败该回的错误码一个不少）。
     * 这个缺陷是被 {@code AdminHttpIT} 抓到的：它数的是库里的行，而不是返回值。
     */
    @Transactional(noRollbackFor = TmException.class)
    public LoginResult login(String username, String password, String ip, String userAgent) {
        String name = username == null ? "" : username.trim().toLowerCase();
        LocalDateTime now = LocalDateTime.now(databaseZone);
        AdminUser admin = admins.findByUsername(name).orElse(null);
        if (admin == null) {
            log.warn("后台登录失败（用户名不存在）username={} ip={}", name, ip);
            throw new TmException(ErrorCode.UNAUTHORIZED, "用户名或口令不正确");
        }
        if (admin.getStatus() != AdminStatus.ACTIVE) {
            log.warn("后台登录被拒（账号已停用）adminId={} ip={}", admin.getId(), ip);
            throw new TmException(ErrorCode.ACCOUNT_SUSPENDED, "adminId=" + admin.getId());
        }
        if (admin.getLockedUntil() != null && admin.getLockedUntil().isAfter(now)) {
            log.warn("后台登录被拒（锁定中，到 {}）adminId={} ip={}",
                    admin.getLockedUntil(), admin.getId(), ip);
            throw new TmException(ErrorCode.RATE_LIMIT_EXCEEDED,
                    "连续登录失败已被锁定，解锁时间 " + admin.getLockedUntil());
        }
        if (!PasswordHashes.verify(password, admin.getPasswordHash())) {
            recordFailure(admin, now, ip);
            throw new TmException(ErrorCode.UNAUTHORIZED, "用户名或口令不正确");
        }

        // 口令正确：清零失败计数与锁定（一次成功登录应当抹掉之前的失败历史，
        // 否则「4 次失败 + 成功 + 1 次失败」就会被锁，而那是运维的正常操作节奏）。
        admins.updateLoginState(admin.getId(), 0, null, now);
        if (PasswordHashes.needsRehash(admin.getPasswordHash())) {
            // 迭代数升级只在校验成功那一刻可行——那时明文还在这一个栈帧里。
            admins.updatePasswordHash(admin.getId(), PasswordHashes.hash(password));
            log.info("后台口令哈希已升级到当前迭代数 adminId={}", admin.getId());
        }

        String token = OpaqueToken.generate(SESSION_PREFIX);
        LocalDateTime expiresAt = now.plus(properties.getSessionTtl());
        AdminSession session = new AdminSession();
        session.setId(idGenerator.nextId());
        session.setAdminId(admin.getId());
        session.setTokenHash(OpaqueToken.hash(token));
        session.setExpiresAt(expiresAt);
        session.setCreatedAt(now);
        session.setIp(ip);
        session.setUserAgent(truncate(userAgent, 255));
        sessions.insert(session);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ip", String.valueOf(ip));
        detail.put("expiresAt", expiresAt.toString());
        audit(admin.getId(), admin.getUsername(), ACTION_LOGIN, TARGET_ADMIN, admin.getId(),
                ip, detail);
        log.info("后台登录成功 adminId={} username={} ip={} 有效期至 {}",
                admin.getId(), admin.getUsername(), ip, expiresAt);
        return new LoginResult(token, admin, expiresAt);
    }

    /**
     * 用会话凭证反查身份 —— 每一个 {@code /v1/admin} 请求都会走这里。
     *
     * <p>{@code clientIp} 由调用方（HTTP 层）给出并放进 {@link AdminContext}：
     * 审计行需要它，而它是「处理这个请求的整条路径上不变」的事实。
     */
    @Transactional
    public AdminContext authenticate(String token, String clientIp) {
        if (token == null || token.isBlank()) {
            throw new TmException(ErrorCode.UNAUTHORIZED, "缺少后台会话凭证");
        }
        if (!OpaqueToken.hasPrefix(SESSION_PREFIX, token)) {
            // 用「前缀不对」与「查不到」统一回 40102：把 JWT 或 api_key 贴到后台接口上
            // 是很常见的误操作，而两者的处理方式相同（换一个凭证），不值得分辨。
            throw new TmException(ErrorCode.INVALID_TOKEN_FORMAT,
                    "不是后台会话凭证 " + OpaqueToken.fingerprint(SESSION_PREFIX, token));
        }
        LocalDateTime now = LocalDateTime.now(databaseZone);
        AdminSession session = sessions.findByTokenHash(OpaqueToken.hash(token)).orElseThrow(() ->
                new TmException(ErrorCode.INVALID_TOKEN_FORMAT, "会话不存在（已登出或从未签发）"));
        if (!session.getExpiresAt().isAfter(now)) {
            throw new TmException(ErrorCode.TOKEN_EXPIRED,
                    "后台会话已于 " + session.getExpiresAt() + " 过期");
        }
        AdminUser admin = admins.findById(session.getAdminId()).orElseThrow(() ->
                new TmException(ErrorCode.INVALID_TOKEN_FORMAT, "会话对应的账号不存在"));
        if (admin.getStatus() != AdminStatus.ACTIVE) {
            // 停用会连同会话一起删（见 setAdminStatus），所以走到这里通常意味着
            // 「有人直接改了库」。仍然拒绝，并且不回「凭证无效」——
            // 让运维看到真实原因比隐藏这一点更重要。
            throw new TmException(ErrorCode.ACCOUNT_SUSPENDED, "adminId=" + admin.getId());
        }
        sessions.touch(session.getId(), now);
        return AdminContext.of(admin, clientIp);
    }

    /** 登出。<b>幂等</b>：凭证已经失效时也返回成功（见 {@code AdminSessionRepository}）。 */
    @Transactional
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        String hash = OpaqueToken.hash(token);
        AdminSession session = sessions.findByTokenHash(hash).orElse(null);
        if (session == null) {
            return;
        }
        sessions.deleteByTokenHash(hash);
        AdminUser admin = admins.findById(session.getAdminId()).orElse(null);
        audit(session.getAdminId(), admin == null ? "<unknown>" : admin.getUsername(),
                ACTION_LOGOUT, TARGET_ADMIN, session.getAdminId(), session.getIp(),
                Map.of());
    }

    /**
     * 首次启动时创建第一个管理员 —— <b>只在账号表为空时生效</b>。
     *
     * <p>没有这一步就得往仓库里放一份带口令的建号脚本，或者手工拼一条
     * {@code INSERT}（而 {@code password_hash} 的格式是 {@code pbkdf2-sha256$…}，
     * 手拼的后果是「怎么登都登不上」，而报错是 40101）。
     *
     * <p>「表为空才生效」这条约束是它安全的原因：口令即使泄漏，也没有可用的时间窗口——
     * 它只在初始化那一次被读到。
     *
     * @return 是否真的建了账号（false = 表里已有账号，配置被忽略）
     */
    @Transactional
    public boolean bootstrap() {
        if (admins.count() > 0) {
            return false;
        }
        String username = properties.getBootstrapUsername();
        String password = properties.getBootstrapPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.warn("admin_user 表为空，且未配置 tm.admin.bootstrap.username/password —— "
                    + "后台还没有任何账号，现在没人能登录（配置这两个值后重启即可创建）");
            return false;
        }
        if (!USERNAME.matcher(username.trim().toLowerCase()).matches()
                || password.length() < properties.getMinPasswordLength()) {
            log.error("tm.admin.bootstrap.* 不合法（用户名需 3-32 位小写字母/数字/下划线，"
                    + "口令至少 {} 位），未创建任何账号", properties.getMinPasswordLength());
            return false;
        }
        AdminUser admin = newAdmin(username, password,
                properties.getBootstrapDisplayName(), AdminRole.SUPER);
        admins.insert(admin);
        // 这一步没有可写的 admin_id（是他建了他自己），所以只有日志、没有审计行。
        // 这不是缺口：审计表的第一行必然来自某个已经存在的身份，
        // 而「谁创建了第一个管理员」的事实存在于部署记录里（是运维配的那两个环境变量）。
        log.warn("已创建第一个后台账号（SUPER）username={} —— 请立即登录并改口令，"
                + "然后从配置里删掉 tm.admin.bootstrap.*", admin.getUsername());
        return true;
    }

    // ================================================================== 后台账号管理

    /** 新建后台账号。只有 SUPER 能调。 */
    @Transactional
    public AdminUser createAdmin(AdminContext ctx, String username, String password,
                                 String displayName, AdminRole role) {
        requireSuper(ctx, "只有超级管理员能新建后台账号");
        String name = username == null ? "" : username.trim().toLowerCase();
        if (!USERNAME.matcher(name).matches()) {
            throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "后台用户名需为 3-32 位小写字母/数字/下划线：" + name);
        }
        if (password == null || password.length() < properties.getMinPasswordLength()) {
            throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "后台口令至少 " + properties.getMinPasswordLength() + " 位");
        }
        if (admins.findByUsername(name).isPresent()) {
            throw new TmException(ErrorCode.ADMIN_USERNAME_EXISTS, "username=" + name);
        }
        AdminUser admin = newAdmin(name, password, displayName,
                role == null ? AdminRole.OPS : role);
        admins.insert(admin);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("username", name);
        detail.put("role", admin.getRole().name());
        audit(ctx, ACTION_CREATE_ADMIN, TARGET_ADMIN, admin.getId(), detail);
        return admin;
    }

    /**
     * 停用 / 启用一个后台账号。只有 SUPER 能调，且**不能停用自己**。
     *
     * <p>禁止停用自己是防「手一抖把自己锁在门外」，而那种情况没有自助恢复路径
     * （要恢复得直接改库）。停用别人时连带删掉他的全部会话：
     * 停用的意义就是「立刻没有权限」，留着旧会话等于没停。
     */
    @Transactional
    public void setAdminStatus(AdminContext ctx, long adminId, AdminStatus status) {
        requireSuper(ctx, "只有超级管理员能停用后台账号");
        AdminUser target = admins.findById(adminId).orElseThrow(() ->
                new TmException(ErrorCode.NOT_FOUND, "后台账号不存在 adminId=" + adminId));
        if (target.getId() == ctx.adminId()) {
            throw new TmException(ErrorCode.SELF_OPERATION, "不能停用自己 adminId=" + adminId);
        }
        AdminStatus from = target.getStatus();
        admins.updateStatus(adminId, status);
        int killed = 0;
        if (status == AdminStatus.DISABLED) {
            killed = sessions.deleteByAdminId(adminId);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("username", target.getUsername());
        detail.put("from", from.name());
        detail.put("to", status.name());
        detail.put("sessionsKilled", killed);
        audit(ctx, ACTION_ADMIN_STATUS, TARGET_ADMIN, adminId, detail);
    }

    public Page<AdminUser> listAdmins(AdminContext ctx, int limit, String cursor) {
        requireSuper(ctx, "只有超级管理员能查看后台账号列表");
        Long before = cursor == null ? null : PageCursors.decodeAdminAccount(cursor).id();
        return page(limit, before, admins::page, PageCursors::encodeAdminAccount);
    }

    /**
     * 当前身份对应的那一行（{@code /v1/admin/me}）。
     *
     * <p>与 {@link #getAdmin} 的差别是<b>不做角色分级</b>：看自己是谁不需要权限，
     * 否则 OPS 连「我是谁、我是什么角色」都读不到，前端也就无从隐藏它没有权限的页面。
     * 它读的是 {@code created_at}/{@code last_login_at} 这两个不在
     * {@link AdminContext} 里的字段，所以这里确实要回一次库。
     */
    public AdminUser currentAdmin(AdminContext ctx) {
        return admins.findById(ctx.adminId()).orElseThrow(() ->
                new TmException(ErrorCode.INVALID_TOKEN_FORMAT,
                        "会话对应的账号不存在 adminId=" + ctx.adminId()));
    }

    public AdminUser getAdmin(AdminContext ctx, long adminId) {
        requireSuper(ctx, "只有超级管理员能查看后台账号");
        return admins.findById(adminId).orElseThrow(() ->
                new TmException(ErrorCode.NOT_FOUND, "后台账号不存在 adminId=" + adminId));
    }

    // ================================================================== 参与者（人 + Agent）

    /**
     * 参与者列表。
     *
     * <p><b>它同时是「用户管理」与「Agent 管理」</b>，因为 Actor 只有一张表：
     * 按 {@code actor_type=AGENT} 过滤就是 Agent 列表。为 Agent 另做一套接口
     * 会把「对等」从后台这一侧破功——而后台恰好是最容易长出「Agent 特例」的地方。
     */
    public Page<Actor> listActors(AdminContext ctx, int limit, String cursor,
                                  ActorType actorType, ActorStatus status, String handlePrefix) {
        Long before = cursor == null ? null : PageCursors.decodeAdminActor(cursor).id();
        return page(limit, before,
                (id, n) -> actors.pageForAdmin(id, n, actorType, status, handlePrefix),
                PageCursors::encodeAdminActor);
    }

    /** 参与者详情：Actor 本体 + Agent 扩展（人没有后者，返回 null）。 */
    public ActorDetail getActor(long actorId) {
        Actor actor = actors.findById(actorId).orElseThrow(() ->
                new TmException(ErrorCode.ACTOR_NOT_FOUND, "actorId=" + actorId));
        return new ActorDetail(actor, agentProfiles.find(actorId).orElse(null));
    }

    /**
     * 停用 / 恢复一个参与者 —— 这就是「可封禁」（DESIGN §14 的 M9 验收标准）。
     *
     * <p><b>与用户端写的是同一列</b>（{@code actor.status}）：封禁不是一个后台专属状态，
     * 而是「这个账号现在不能用了」——长连接鉴权、REST 鉴权、发消息全部读它，
     * 所以后台封完号之后，那个人的下一次请求就会被拒，不需要任何同步机制。
     *
     * <p>重复调用也写审计（{@code from == to}）：审计要回答的是「谁点过这个按钮」，
     * 而不是「状态变了吗」。少写一条会让「运营 A 点过一次、运营 B 又点一次」
     * 在日志里只留下一个人。
     */
    @Transactional
    public Actor setActorStatus(AdminContext ctx, long actorId, ActorStatus status, String reason) {
        Actor actor = actors.findById(actorId).orElseThrow(() ->
                new TmException(ErrorCode.ACTOR_NOT_FOUND, "actorId=" + actorId));
        ActorStatus from = actor.getStatus();
        actor.setStatus(status);
        actors.update(actor);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("handle", actor.getHandle());
        detail.put("actorType", String.valueOf(actor.getActorType()));
        detail.put("from", String.valueOf(from));
        detail.put("to", String.valueOf(status));
        putIfPresent(detail, "reason", reason);
        audit(ctx, ACTION_ACTOR_STATUS,
                actor.getActorType() == ActorType.AGENT ? TARGET_AGENT : TARGET_ACTOR,
                actorId, detail);
        log.info("后台变更参与者状态 actorId={} handle={} {} -> {} by={}",
                actorId, actor.getHandle(), from, status, ctx.username());
        return actor;
    }

    // ================================================================== 内容

    /** 内容列表（按 id 倒序的全量分页，见 {@code PostRepository#pageForModeration}）。 */
    public Page<Post> listPosts(AdminContext ctx, int limit, String cursor) {
        Long before = cursor == null ? null : PageCursors.decodeAdminPost(cursor).id();
        return page(limit, before, posts::pageForModeration, PageCursors::encodeAdminPost);
    }

    /**
     * 删帖（审核）。走的正是作者删帖那条级联路径（{@link PostDeletionPort#deleteAsAdmin}）。
     *
     * <p>审计记的是 {@code POST} 而不是 {@code ACTOR}：
     * 内容是「删掉就没了」的对象，所以 {@code target_id} 必须是那条动态的 id，
     * 而不是作者的 id——否则「某个作者被删过哪些动态」就查不出来了。
     */
    @Transactional
    public void deletePost(AdminContext ctx, long postId, String reason) {
        Post post = posts.findById(postId).orElseThrow(() ->
                new TmException(ErrorCode.POST_NOT_FOUND, "postId=" + postId));
        Long authorId = post.getAuthorId();
        postDeletion.deleteAsAdmin(postId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("authorId", authorId);
        putIfPresent(detail, "reason", reason);
        audit(ctx, ACTION_POST_DELETE, TARGET_POST, postId, detail);
        log.info("后台删除动态 postId={} author={} by={}", postId, authorId, ctx.username());
    }

    // ================================================================== 审计

    public Page<AdminAuditLog> listAuditLogs(AdminContext ctx, int limit, String cursor,
                                             Long adminId, String action, String targetType,
                                             Long targetId) {
        Long before = cursor == null ? null : PageCursors.decodeAdminAudit(cursor).id();
        return page(limit, before,
                (id, n) -> auditLogs.page(id, n, adminId, action, targetType, targetId),
                PageCursors::encodeAdminAudit);
    }

    // ================================================================== 内部

    /**
     * 统一的「取一页」：多取一条来判断还有没有下一页。
     *
     * <p>游标由<b>数据库返回的最后一行</b>生成，而不是由渲染后的结果生成：
     * 列表为空时不能给出游标（那会让客户端以为还能继续翻），
     * 而「多取的那一条」只用于判断 {@code hasMore}，不参与编码。
     */
    private <T> Page<T> page(int limit, Long before, Fetcher<T> fetcher, CursorEncoder encoder) {
        int size = pageSize(limit);
        List<T> rows = new ArrayList<>(fetcher.fetch(before, size + 1));
        boolean hasMore = rows.size() > size;
        if (hasMore) {
            rows = rows.subList(0, size);
        }
        String next = null;
        if (hasMore && !rows.isEmpty()) {
            next = encoder.encode(idOf(rows.get(rows.size() - 1)));
        }
        return new Page<>(rows, next, hasMore);
    }

    /** 取一页的实现（{@code beforeId} 为 null 表示第一页）。 */
    @FunctionalInterface
    private interface Fetcher<T> {
        List<T> fetch(Long beforeId, int limit);
    }

    @FunctionalInterface
    private interface CursorEncoder {
        String encode(long id);
    }

    /**
     * 从一行里取出「游标用的那个 id」。
     *
     * <p>用 {@code instanceof} 而不是给四张表各写一个重载：四个 id 的语义完全相同
     * （Snowflake 主键），差别只在类型系统不认识「它们都是分页键」。
     * 未知类型直接抛——静默取错 id 会让分页永远停在同一位置，而那种 bug
     * 只在列表长到第二页时才出现。
     */
    private static long idOf(Object row) {
        if (row instanceof AdminUser user) {
            return user.getId();
        }
        if (row instanceof AdminAuditLog entry) {
            return entry.getId();
        }
        if (row instanceof Actor actor) {
            return actor.getId();
        }
        if (row instanceof Post post) {
            return post.getId();
        }
        throw new IllegalArgumentException("分页游标不知道该取哪个 id: " + row.getClass());
    }

    private int pageSize(int limit) {
        if (limit <= 0) {
            return properties.getPageSize();
        }
        // 夹住而不是报错：客户端要 1000 条时给它 100 条并让它继续翻页，
        // 比回一个 40002 更有用（而它自己也知道该怎么继续）。
        return Math.min(limit, properties.getMaxPageSize());
    }

    private void requireSuper(AdminContext ctx, String why) {
        if (!ctx.isSuper()) {
            throw new TmException(ErrorCode.PERMISSION_DENIED, why + "（当前 role=" + ctx.role() + "）");
        }
    }

    private void recordFailure(AdminUser admin, LocalDateTime now, String ip) {
        int failed = (admin.getFailedAttempts() == null ? 0 : admin.getFailedAttempts()) + 1;
        LocalDateTime lockedUntil = null;
        if (failed >= properties.getMaxLoginFailures()) {
            lockedUntil = now.plus(properties.getLockoutDuration());
            log.warn("后台账号连续失败 {} 次，锁定至 {} adminId={} ip={}",
                    failed, lockedUntil, admin.getId(), ip);
            // 计数清零：锁定解除后重新给 maxLoginFailures 次机会，
            // 而不是「解锁瞬间又差一次就再锁」。
            failed = 0;
        }
        admins.updateLoginState(admin.getId(), failed, lockedUntil, admin.getLastLoginAt());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ip", String.valueOf(ip));
        detail.put("failedAttempts", failed);
        if (lockedUntil != null) {
            detail.put("lockedUntil", lockedUntil.toString());
        }
        audit(admin.getId(), admin.getUsername(), ACTION_LOGIN_FAILED, TARGET_ADMIN,
                admin.getId(), ip, detail);
    }

    private AdminUser newAdmin(String username, String password, String displayName,
                               AdminRole role) {
        LocalDateTime now = LocalDateTime.now(databaseZone);
        AdminUser admin = new AdminUser();
        admin.setId(idGenerator.nextId());
        admin.setUsername(username.trim().toLowerCase());
        admin.setDisplayName(displayName == null || displayName.isBlank()
                ? admin.getUsername() : displayName.trim());
        admin.setPasswordHash(PasswordHashes.hash(password));
        admin.setRole(role);
        admin.setStatus(AdminStatus.ACTIVE);
        admin.setFailedAttempts(0);
        admin.setCreatedAt(now);
        return admin;
    }

    /** 从 {@link AdminContext} 出发的审计（绝大多数调用点）。 */
    private void audit(AdminContext ctx, String action, String targetType, Long targetId,
                       Map<String, Object> detail) {
        audit(ctx.adminId(), ctx.username(), action, targetType, targetId, ctx.clientIp(), detail);
    }

    /**
     * 写一条审计行。
     *
     * <p>{@code detail} 由 Map 序列化成 JSON（JSON 列），而不是拼字符串：
     * 拼字符串的后果是「某天有人在 reason 里写了个引号」，那行 JSON 就坏了，
     * 而坏掉的审计行要在半年后想查它时才会被发现。
     */
    private void audit(long adminId, String adminName, String action, String targetType,
                       Long targetId, String ip, Map<String, Object> detail) {
        AdminAuditLog row = new AdminAuditLog();
        row.setId(idGenerator.nextId());
        row.setAdminId(adminId);
        row.setAdminName(truncate(adminName, 64));
        row.setAction(action);
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setDetail(detail == null || detail.isEmpty() ? null : Json.write(detail));
        row.setIp(truncate(ip, 64));
        row.setCreatedAt(LocalDateTime.now(databaseZone));
        auditLogs.insert(row);
    }

    private static void putIfPresent(Map<String, Object> detail, String key, String value) {
        if (value != null && !value.isBlank()) {
            detail.put(key, truncate(value, 255));
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
