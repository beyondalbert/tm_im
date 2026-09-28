package com.tm.im.core.admin;

import com.tm.im.common.crypto.OpaqueToken;
import com.tm.im.common.crypto.PasswordHashes;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.entity.AdminUser;
import com.tm.im.domain.entity.Post;
import com.tm.im.domain.enums.ActorStatus;
import com.tm.im.domain.enums.ActorType;
import com.tm.im.domain.enums.AdminRole;
import com.tm.im.domain.enums.AdminStatus;
import com.tm.im.domain.repository.ActorRepository;
import com.tm.im.domain.repository.AgentProfileRepository;
import com.tm.im.domain.repository.PostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 后台服务的规则测试（M9）。
 *
 * <p>这里钉住的是<b>规则</b>：谁能登录、封禁写的是哪个字段、审计里留下了什么、
 * 哪些动作被拒绝。SQL 层面的东西（索引、事务边界、JSON 列）由
 * {@code AdminHttpIT} 在真实 MySQL 上覆盖——两层的分工与本仓库其它模块一致。
 */
class AdminServiceTest {

    private static final String PW = "it-admin-password-1";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final InMemoryAdminStore store = new InMemoryAdminStore();
    private final RecordingActors actors = new RecordingActors();
    private final RecordingPosts posts = new RecordingPosts();
    private final AdminProperties properties = new AdminProperties();
    private ExecutorService fanout;
    private AdminService admins;

    @BeforeEach
    void setUp() {
        fanout = Executors.newSingleThreadExecutor();
        properties.setMinPasswordLength(8);
        admins = new AdminService(store.users, store.sessions, store.audits, actors,
                new EmptyAgentProfiles(), posts,
                new RecordingPostDeletion(posts),
                store.idGenerator(), properties, ZONE);
    }

    // ------------------------------------------------------------------ 登录

    @Test
    @DisplayName("登录成功：签发 adm_ 会话，明文只在这里出现，库里只有哈希")
    void loginIssuesSessionWithHashedToken() {
        AdminUser root = store.users.put("root", PW, AdminRole.SUPER, AdminStatus.ACTIVE);

        AdminService.LoginResult result = admins.login("root", PW, "10.0.0.1", "JUnit");

        assertThat(result.token()).startsWith(AdminService.SESSION_PREFIX);
        assertThat(result.admin().getId()).isEqualTo(root.getId());
        // 会话表里存的是哈希，而不是明文：读库的人不该拿到能直接用的凭证
        assertThat(store.sessions.findByTokenHash(OpaqueToken.hash(result.token()))).isPresent();
        assertThat(store.sessions.findByTokenHash(result.token())).isEmpty();
        // 登录本身也要留痕（谁、从哪、会话到什么时候）
        assertThat(store.audits.withAction(AdminService.ACTION_LOGIN)).hasSize(1);
        assertThat(store.audits.last().getDetail()).contains("10.0.0.1");
        assertThat(store.users.get(root.getId()).getLastLoginAt()).isNotNull();
    }

    @Test
    @DisplayName("用户名不存在与口令不对回同一个 40101 —— 否则登录接口就是用户名枚举器")
    void unknownUserAndWrongPasswordAreIndistinguishable() {
        store.users.put("root", PW, AdminRole.SUPER, AdminStatus.ACTIVE);

        ErrorCode unknown = InMemoryAdminStore.codeOf(
                () -> admins.login("nobody", PW, "10.0.0.1", null));
        ErrorCode wrong = InMemoryAdminStore.codeOf(
                () -> admins.login("root", "wrong-password", "10.0.0.1", null));

        assertThat(unknown).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThat(wrong).isEqualTo(ErrorCode.UNAUTHORIZED);
        // 口令错的那次必须留痕，用户名不存在的那次没有身份可写（只进日志）
        assertThat(store.audits.withAction(AdminService.ACTION_LOGIN_FAILED)).hasSize(1);
    }

    @Test
    @DisplayName("账号被停用时回 40301 而不是 40101：这是需要运维知道的事实")
    void disabledAccountIsReportedAsSuspended() {
        store.users.put("root", PW, AdminRole.SUPER, AdminStatus.DISABLED);

        assertThat(InMemoryAdminStore.codeOf(() -> admins.login("root", PW, "10.0.0.1", null)))
                .isEqualTo(ErrorCode.ACCOUNT_SUSPENDED);
    }

    @Test
    @DisplayName("连续失败到阈值即锁定；锁定期内正确口令也被拒，且不再累计失败次数")
    void lockoutAfterTooManyFailures() {
        AdminUser root = store.users.put("root", PW, AdminRole.SUPER, AdminStatus.ACTIVE);
        properties.setMaxLoginFailures(3);
        properties.setLockoutDuration(Duration.ofMinutes(15));

        for (int i = 0; i < 3; i++) {
            assertThat(InMemoryAdminStore.codeOf(
                    () -> admins.login("root", "bad", "10.0.0.1", null)))
                    .isEqualTo(ErrorCode.UNAUTHORIZED);
        }
        assertThat(store.users.get(root.getId()).getLockedUntil()).isNotNull();
        assertThat(store.audits.withAction(AdminService.ACTION_LOGIN_FAILED)).hasSize(3);

        // 锁定期间：正确口令也不行，且**不再**新增审计行（否则攻击者可以零成本写库）
        assertThat(InMemoryAdminStore.codeOf(() -> admins.login("root", PW, "10.0.0.1", null)))
                .isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED);
        assertThat(store.audits.withAction(AdminService.ACTION_LOGIN_FAILED)).hasSize(3);
        assertThat(store.sessions.size()).isZero();
    }

    @Test
    @DisplayName("登录成功清零失败计数：4 次失败 + 一次成功，不该处在「再错一次就锁」的状态")
    void successfulLoginResetsFailureCount() {
        AdminUser root = store.users.put("root", PW, AdminRole.SUPER, AdminStatus.ACTIVE);
        properties.setMaxLoginFailures(5);
        for (int i = 0; i < 4; i++) {
            InMemoryAdminStore.codeOf(() -> admins.login("root", "bad", "10.0.0.1", null));
        }

        admins.login("root", PW, "10.0.0.1", null);

        assertThat(store.users.get(root.getId()).getFailedAttempts()).isZero();
        assertThat(store.users.get(root.getId()).getLockedUntil()).isNull();
    }

    @Test
    @DisplayName("底层哈希迭代数偏低时，登录顺手升级（明文只在这一刻存在）")
    void loginUpgradesWeakPasswordHash() {
        AdminUser root = store.users.put("root", PW, AdminRole.SUPER, AdminStatus.ACTIVE);
        assertThat(PasswordHashes.iterationsOf(store.users.get(root.getId()).getPasswordHash()))
                .isEqualTo(1000);

        admins.login("root", PW, "10.0.0.1", null);

        assertThat(PasswordHashes.iterationsOf(store.users.get(root.getId()).getPasswordHash()))
                .isEqualTo(PasswordHashes.DEFAULT_ITERATIONS);
    }

    // ------------------------------------------------------------------ 会话鉴权

    @Test
    @DisplayName("鉴权：拿 JWT 或 api_key 来后台一律 40102（前缀不对），且不泄漏凭证本身")
    void otherCredentialKindsAreRejectedByPrefix() {
        store.users.put("root", PW, AdminRole.SUPER, AdminStatus.ACTIVE);

        TmException e = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> admins.authenticate("sk_live_9f2c1d7a4b8e3f60", "10.0.0.1"), TmException.class);

        assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_TOKEN_FORMAT);
        assertThat(e.detail()).doesNotContain("9f2c1d7a");
    }

    @Test
    @DisplayName("会话过期回 40103（客户端该重新登录），而不是 40102（该查拼装代码）")
    void expiredSessionIsTokenExpired() {
        AdminUser root = store.users.put("root", PW, AdminRole.SUPER, AdminStatus.ACTIVE);
        String token = AdminService.SESSION_PREFIX + "expired-token";
        store.sessions.put(root.getId(), token, LocalDateTime.now().minusMinutes(1));

        assertThat(InMemoryAdminStore.codeOf(() -> admins.authenticate(token, "10.0.0.1")))
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    @DisplayName("登出是幂等的：凭证已经失效也返回成功，且删掉那一行")
    void logoutIsIdempotent() {
        store.users.put("root", PW, AdminRole.SUPER, AdminStatus.ACTIVE);
        AdminService.LoginResult result = admins.login("root", PW, "10.0.0.1", null);
        assertThat(store.sessions.size()).isEqualTo(1);

        admins.logout(result.token());
        admins.logout(result.token());
        admins.logout(null);

        assertThat(store.sessions.size()).isZero();
        assertThat(store.audits.withAction(AdminService.ACTION_LOGOUT)).hasSize(1);
    }

    @Test
    @DisplayName("鉴权成功会记一次 last_seen_at（观测字段），失败不影响鉴权结果")
    void authenticateTouchesSession() {
        store.users.put("root", PW, AdminRole.SUPER, AdminStatus.ACTIVE);
        AdminService.LoginResult result = admins.login("root", PW, "10.0.0.1", null);

        AdminContext ctx = admins.authenticate(result.token(), "10.0.0.2");

        assertThat(ctx.username()).isEqualTo("root");
        assertThat(ctx.clientIp()).isEqualTo("10.0.0.2");
        assertThat(store.sessions.touchCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 封禁

    @Test
    @DisplayName("封禁写的是 actor.status（用户端鉴权读的同一列），并留下审计")
    void suspendActorWritesTheSameColumnUsersRead() {
        Actor alice = actors.put(42L, "alice", ActorType.HUMAN, ActorStatus.ACTIVE);
        AdminContext root = contextOf("root");

        admins.setActorStatus(root, 42L, ActorStatus.SUSPENDED, "刷屏");

        assertThat(actors.get(42L).getStatus()).isEqualTo(ActorStatus.SUSPENDED);
        assertThat(actors.updateCount).isEqualTo(1);
        var audit = store.audits.withAction(AdminService.ACTION_ACTOR_STATUS).get(0);
        assertThat(audit.getTargetType()).isEqualTo(AdminService.TARGET_ACTOR);
        assertThat(audit.getTargetId()).isEqualTo(42L);
        assertThat(audit.getDetail()).contains("ACTIVE").contains("SUSPENDED").contains("刷屏");
        assertThat(alice.getId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("封禁 Agent 记的是 AGENT 目标类型，但走的是同一段代码（对等）")
    void suspendingAgentUsesTheSamePath() {
        actors.put(43L, "weather_bot", ActorType.AGENT, ActorStatus.ACTIVE);

        admins.setActorStatus(contextOf("root"), 43L, ActorStatus.SUSPENDED, null);

        assertThat(store.audits.last().getTargetType()).isEqualTo(AdminService.TARGET_AGENT);
        assertThat(store.audits.last().getDetail()).doesNotContain("reason");
    }

    @Test
    @DisplayName("封不存在的号回 40401，而不是静默成功")
    void suspendingUnknownActorIsNotFound() {
        assertThat(InMemoryAdminStore.codeOf(
                () -> admins.setActorStatus(contextOf("root"), 999L, ActorStatus.SUSPENDED, null)))
                .isEqualTo(ErrorCode.ACTOR_NOT_FOUND);
    }

    @Test
    @DisplayName("重复封禁仍然写审计：审计回答的是「谁点过」，不是「变了吗」")
    void repeatedSuspendStillAudited() {
        actors.put(42L, "alice", ActorType.HUMAN, ActorStatus.SUSPENDED);
        AdminContext root = contextOf("root");

        admins.setActorStatus(root, 42L, ActorStatus.SUSPENDED, null);
        admins.setActorStatus(root, 42L, ActorStatus.SUSPENDED, null);

        assertThat(store.audits.withAction(AdminService.ACTION_ACTOR_STATUS)).hasSize(2);
    }

    // ------------------------------------------------------------------ 参与者列表

    @Test
    @DisplayName("列表可按类型过滤（Agent 列表就是 actor_type=2），游标只在还有下一页时给出")
    void listActorsFiltersByTypeAndPaginates(org.junit.jupiter.api.TestInfo info) {
        for (int i = 0; i < 5; i++) {
            actors.put(100 + i, "user_" + i, ActorType.HUMAN, ActorStatus.ACTIVE);
        }
        actors.put(200L, "weather_bot", ActorType.AGENT, ActorStatus.ACTIVE);
        AdminContext root = contextOf("root");

        AdminService.Page<Actor> agents = admins.listActors(root, 10, null,
                ActorType.AGENT, null, null);
        assertThat(agents.items()).extracting(Actor::getHandle).containsExactly("weather_bot");
        assertThat(agents.hasMore()).isFalse();
        assertThat(agents.nextCursor()).isNull();

        AdminService.Page<Actor> firstPage = admins.listActors(root, 2, null, null, null, null);
        assertThat(firstPage.items()).hasSize(2);
        assertThat(firstPage.hasMore()).isTrue();
        // 第二页从游标开始，且不与第一页重复
        AdminService.Page<Actor> secondPage = admins.listActors(root, 2, firstPage.nextCursor(),
                null, null, null);
        assertThat(secondPage.items()).extracting(Actor::getId)
                .doesNotContainAnyElementsOf(firstPage.items().stream().map(Actor::getId).toList());
    }

    @Test
    @DisplayName("handle 前缀过滤：运营查人几乎总是「记得开头」")
    void listActorsFiltersByHandlePrefix() {
        actors.put(1L, "alice", ActorType.HUMAN, ActorStatus.ACTIVE);
        actors.put(2L, "alicia", ActorType.HUMAN, ActorStatus.ACTIVE);
        actors.put(3L, "bob", ActorType.HUMAN, ActorStatus.ACTIVE);

        AdminService.Page<Actor> page = admins.listActors(contextOf("root"), 10, null,
                null, null, "ali");

        assertThat(page.items()).extracting(Actor::getHandle).containsExactlyInAnyOrder("alice", "alicia");
    }

    @Test
    @DisplayName("详情带上 Agent 扩展；人没有这一项（null），而不是造一个空对象")
    void actorDetailIncludesAgentProfileOnlyForAgents() {
        actors.put(42L, "alice", ActorType.HUMAN, ActorStatus.ACTIVE);
        actors.put(43L, "weather_bot", ActorType.AGENT, ActorStatus.ACTIVE);

        assertThat(admins.getActor(42L).agentProfile()).isNull();
        assertThat(admins.getActor(43L).agentProfile()).isNotNull();
        assertThat(InMemoryAdminStore.codeOf(() -> admins.getActor(999L)))
                .isEqualTo(ErrorCode.ACTOR_NOT_FOUND);
    }

    // ------------------------------------------------------------------ 内容

    @Test
    @DisplayName("删帖走作者那条级联路径，审计记的是 POST 而不是作者")
    void deletePostUsesTheSameCascadeAndAuditsThePost() {
        posts.put(newPost(5L, 42L));
        AdminContext root = contextOf("root");

        admins.deletePost(root, 5L, "违规内容");

        assertThat(posts.get(5L)).isNull();
        var audit = store.audits.last();
        assertThat(audit.getAction()).isEqualTo(AdminService.ACTION_POST_DELETE);
        assertThat(audit.getTargetType()).isEqualTo(AdminService.TARGET_POST);
        assertThat(audit.getTargetId()).isEqualTo(5L);
        assertThat(audit.getDetail()).contains("42").contains("违规内容");
    }

    @Test
    @DisplayName("删不存在的动态回 40404")
    void deleteUnknownPostIsNotFound() {
        assertThat(InMemoryAdminStore.codeOf(() -> admins.deletePost(contextOf("root"), 5L, null)))
                .isEqualTo(ErrorCode.POST_NOT_FOUND);
    }

    // ------------------------------------------------------------------ 后台账号与权限

    @Test
    @DisplayName("OPS 建不了号、看不了账号列表：40302，且这条判据在服务层而不在控制器")
    void opsCannotManageAccounts() {
        AdminContext ops = opsContext();

        assertThat(InMemoryAdminStore.codeOf(
                () -> admins.createAdmin(ops, "newbie", "newbie-password", null, null)))
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(InMemoryAdminStore.codeOf(() -> admins.listAdmins(ops, 10, null)))
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("建号：用户名形状与口令长度都要过关，重名回 40909")
    void createAdminValidatesInput() {
        AdminContext root = contextOf("root");

        assertThat(InMemoryAdminStore.codeOf(
                () -> admins.createAdmin(root, "Bad Name", "long-enough-pw", null, null)))
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
        assertThat(InMemoryAdminStore.codeOf(
                () -> admins.createAdmin(root, "newbie", "short", null, null)))
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
        assertThat(InMemoryAdminStore.codeOf(
                () -> admins.createAdmin(root, "root", "long-enough-pw", null, null)))
                .isEqualTo(ErrorCode.ADMIN_USERNAME_EXISTS);

        AdminUser created = admins.createAdmin(root, "Newbie", "long-enough-pw", null, null);
        assertThat(created.getUsername()).isEqualTo("newbie");
        assertThat(created.getRole()).isEqualTo(AdminRole.OPS);
        assertThat(created.getStatus()).isEqualTo(AdminStatus.ACTIVE);
        // 明文口令不进任何响应字段（视图里只有 username/role/status…）
        assertThat(created.getPasswordHash()).startsWith(PasswordHashes.ALGORITHM);
        assertThat(store.audits.withAction(AdminService.ACTION_CREATE_ADMIN)).hasSize(1);
    }

    @Test
    @DisplayName("停用后台账号会连带踢掉它的全部会话（同一事务），且不能停用自己")
    void disablingAdminKillsItsSessions() {
        AdminUser target = store.users.put("ops1", PW, AdminRole.OPS, AdminStatus.ACTIVE);
        store.sessions.put(target.getId(), AdminService.SESSION_PREFIX + "ops1-token-1",
                LocalDateTime.now().plusHours(1));
        store.sessions.put(target.getId(), AdminService.SESSION_PREFIX + "ops1-token-2",
                LocalDateTime.now().plusHours(1));
        AdminContext root = contextOf("root");

        admins.setAdminStatus(root, target.getId(), AdminStatus.DISABLED);

        assertThat(store.users.get(target.getId()).getStatus()).isEqualTo(AdminStatus.DISABLED);
        assertThat(store.sessions.size()).isZero();
        assertThat(store.audits.last().getDetail()).contains("sessionsKilled").contains("2");

        assertThat(InMemoryAdminStore.codeOf(
                () -> admins.setAdminStatus(root, root.adminId(), AdminStatus.DISABLED)))
                .isEqualTo(ErrorCode.SELF_OPERATION);
    }

    // ------------------------------------------------------------------ 审计查询与 bootstrap

    @Test
    @DisplayName("审计可按管理员 / 动作 / 目标过滤，按 id 倒序")
    void auditLogsAreQueryableByThreeAngles() {
        actors.put(42L, "alice", ActorType.HUMAN, ActorStatus.ACTIVE);
        AdminContext root = contextOf("root");
        admins.setActorStatus(root, 42L, ActorStatus.SUSPENDED, null);
        admins.setActorStatus(root, 42L, ActorStatus.ACTIVE, null);

        AdminService.Page<com.tm.im.domain.entity.AdminAuditLog> byAction =
                admins.listAuditLogs(root, 10, null, null, AdminService.ACTION_ACTOR_STATUS,
                        null, null);
        assertThat(byAction.items()).hasSize(2);
        // 倒序：最后发生的那条在前
        assertThat(byAction.items().get(0).getDetail()).contains("SUSPENDED");

        AdminService.Page<com.tm.im.domain.entity.AdminAuditLog> byTarget =
                admins.listAuditLogs(root, 10, null, null, null, AdminService.TARGET_ACTOR, 42L);
        assertThat(byTarget.items()).hasSize(2);

        AdminService.Page<com.tm.im.domain.entity.AdminAuditLog> byAdmin =
                admins.listAuditLogs(root, 10, null, 999L, null, null, null);
        assertThat(byAdmin.items()).isEmpty();
    }

    @Test
    @DisplayName("bootstrap 只在表为空时生效一次，之后配置被忽略（口令没有可用窗口）")
    void bootstrapOnlyWhenEmpty() {
        properties.setBootstrapUsername("root");
        properties.setBootstrapPassword("bootstrap-password");

        assertThat(admins.bootstrap()).isTrue();
        assertThat(admins.bootstrap()).isFalse();
        assertThat(store.users.size()).isEqualTo(1);
        assertThat(store.users.findByUsername("root").orElseThrow().getRole())
                .isEqualTo(AdminRole.SUPER);
        // 建号这一步没有可写的 admin_id（是他建了他自己），所以不留审计行
        assertThat(store.audits.size()).isZero();
    }

    @Test
    @DisplayName("bootstrap 配置不合法时不建号，也不让应用起不来")
    void bootstrapRejectsWeakConfig() {
        properties.setBootstrapUsername("root");
        properties.setBootstrapPassword("short");

        assertThat(admins.bootstrap()).isFalse();
        assertThat(store.users.size()).isZero();
    }

    @Test
    @DisplayName("没配 bootstrap 且表为空时只告警（应用照常启动）")
    void bootstrapWithoutConfigIsNoOp() {
        assertThat(admins.bootstrap()).isFalse();
        assertThat(store.users.size()).isZero();
    }

    @Test
    @DisplayName("分页大小被夹到上限，而不是报错")
    void pageSizeIsClampedNotRejected() {
        for (int i = 0; i < 5; i++) {
            actors.put(100 + i, "user_" + i, ActorType.HUMAN, ActorStatus.ACTIVE);
        }
        properties.setMaxPageSize(3);

        AdminService.Page<Actor> page = admins.listActors(contextOf("root"), 1000, null,
                null, null, null);

        assertThat(page.items()).hasSize(3);
        assertThat(page.hasMore()).isTrue();
    }

    // ------------------------------------------------------------------ 夹具

    /**
     * 一个真实存在的 SUPER 上下文。
     *
     * <p>它必须对应库里的一行：审计要写 {@code admin_id}、停用自己那条规则要
     * 「比 id」，而一个不存在的 id 会让那些断言变成「替身没报错」而不是「规则对」。
     * 第一版就是写死的 {@code 1L}，于是「不能停用自己」那条用例拿到的是 40400。
     */
    private AdminContext contextOf(String username) {
        AdminUser row = store.users.findByUsername(username)
                .orElseGet(() -> store.users.put(username, PW, AdminRole.SUPER, AdminStatus.ACTIVE));
        return new AdminContext(row.getId(), row.getUsername(), row.getDisplayName(),
                row.getRole(), "10.0.0.1");
    }

    private AdminContext opsContext() {
        AdminUser row = store.users.findByUsername("ops1")
                .orElseGet(() -> store.users.put("ops1", PW, AdminRole.OPS, AdminStatus.ACTIVE));
        return new AdminContext(row.getId(), row.getUsername(), row.getDisplayName(),
                row.getRole(), "10.0.0.1");
    }

    private static Post newPost(long id, long authorId) {
        Post post = new Post();
        post.setId(id);
        post.setAuthorId(authorId);
        post.setContent("{\"text\":\"hello\"}");
        post.setVisibility(com.tm.im.domain.enums.Visibility.PUBLIC);
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setCreatedAt(LocalDateTime.of(2026, 1, 1, 9, 0));
        return post;
    }

    /** 只实现本测试需要的两个方法：参与者读写与「按 id 倒序」的分页。 */
    private static final class RecordingActors implements ActorRepository {

        private final List<Actor> rows = new ArrayList<>();
        int updateCount;

        Actor put(long id, String handle, ActorType type, ActorStatus status) {
            Actor actor = new Actor();
            actor.setId(id);
            actor.setHandle(handle);
            actor.setDisplayName(handle);
            actor.setActorType(type);
            actor.setStatus(status);
            actor.setCreatedAt(LocalDateTime.of(2026, 1, 1, 8, 0));
            rows.add(actor);
            return actor;
        }

        Actor get(long id) {
            return rows.stream().filter(a -> a.getId() == id).findFirst().orElse(null);
        }

        @Override
        public Optional<Actor> findById(long actorId) {
            return Optional.ofNullable(get(actorId));
        }

        @Override
        public Optional<Actor> findByHandle(String handle) {
            return rows.stream().filter(a -> a.getHandle().equals(handle)).findFirst();
        }

        @Override
        public boolean existsHandle(String handle) {
            return findByHandle(handle).isPresent();
        }

        @Override
        public Actor insert(Actor actor) {
            rows.add(actor);
            return actor;
        }

        @Override
        public void update(Actor actor) {
            updateCount++;
        }

        @Override
        public List<Actor> findByIds(List<Long> actorIds) {
            return rows.stream().filter(a -> actorIds.contains(a.getId())).toList();
        }

        @Override
        public List<Actor> pageForAdmin(Long beforeId, int limit, ActorType actorType,
                                        ActorStatus status, String handlePrefix) {
            String prefix = handlePrefix == null || handlePrefix.isBlank() ? null : handlePrefix;
            return rows.stream()
                    .filter(a -> beforeId == null || a.getId() < beforeId)
                    .filter(a -> actorType == null || a.getActorType() == actorType)
                    .filter(a -> status == null || a.getStatus() == status)
                    .filter(a -> prefix == null || a.getHandle().startsWith(prefix))
                    .sorted((x, y) -> Long.compare(y.getId(), x.getId()))
                    .limit(Math.max(1, limit))
                    .toList();
        }
    }

    /** 只实现删帖要用到的四个方法。 */
    private static final class RecordingPosts implements PostRepository {

        private final List<Post> rows = new ArrayList<>();

        void put(Post post) {
            rows.add(post);
        }

        Post get(long id) {
            return rows.stream().filter(p -> p.getId() == id).findFirst().orElse(null);
        }

        @Override
        public void insert(Post post) {
            rows.add(post);
        }

        @Override
        public Optional<Post> findById(long postId) {
            return Optional.ofNullable(get(postId));
        }

        @Override
        public List<Post> findByIds(java.util.Collection<Long> postIds) {
            return rows.stream().filter(p -> postIds.contains(p.getId())).toList();
        }

        @Override
        public Optional<Post> findByClientPostId(long authorId, String clientPostId) {
            return Optional.empty();
        }

        @Override
        public int countByAuthorSince(long authorId, LocalDateTime since) {
            return 0;
        }

        @Override
        public boolean deleteById(long postId) {
            return rows.removeIf(p -> p.getId() == postId);
        }

        @Override
        public void addLikeCount(long postId, int delta) {
        }

        @Override
        public void addCommentCount(long postId, int delta) {
        }

        @Override
        public List<Post> pageByAuthor(long authorId, Cursor cursor, int limit) {
            return List.of();
        }

        @Override
        public List<Post> pagePublicExcluding(java.util.Collection<Long> excludeAuthors,
                                              Cursor cursor, int limit) {
            return List.of();
        }

        @Override
        public List<Post> pageForModeration(Long beforeId, int limit) {
            return rows.stream()
                    .filter(p -> beforeId == null || p.getId() < beforeId)
                    .sorted((x, y) -> Long.compare(y.getId(), x.getId()))
                    .limit(Math.max(1, limit))
                    .toList();
        }
    }

    /** 人的账号没有 agent_profile 行；本测试只关心 Agent 的那条路径要点 → 空表即可。 */
    private static class EmptyAgentProfiles implements AgentProfileRepository {

        @Override
        public Optional<com.tm.im.domain.entity.AgentProfile> find(long actorId) {
            return Optional.ofNullable(profileFor(actorId));
        }

        private com.tm.im.domain.entity.AgentProfile profileFor(long actorId) {
            if (actorId != 43L) {
                return null;
            }
            com.tm.im.domain.entity.AgentProfile profile = new com.tm.im.domain.entity.AgentProfile();
            profile.setActorId(actorId);
            profile.setOwnerActor(42L);
            profile.setPushMode(com.tm.im.domain.enums.PushMode.WEBHOOK);
            profile.setEndpointUrl("https://agent.example.com/hook");
            profile.setCapabilities("[\"text\"]");
            profile.setRateLimit(60);
            return profile;
        }

        @Override
        public List<com.tm.im.domain.entity.AgentProfile> findByIds(List<Long> actorIds) {
            return List.of();
        }

        @Override
        public List<com.tm.im.domain.entity.AgentProfile> findByOwner(long ownerActor, int limit) {
            return List.of();
        }

        @Override
        public void save(com.tm.im.domain.entity.AgentProfile profile) {
        }
    }

    /**
     * 删帖端口的替身：记录被删的 id。
     *
     * <p>它同时是「后台删帖与作者删帖走同一条级联路径」这件事的证据形式——
     * {@code AdminService} 只认识 {@link com.tm.im.core.plaza.PostDeletionPort}
     * 这一个方法，而不是整个 PlazaService。
     */
    private static final class RecordingPostDeletion implements com.tm.im.core.plaza.PostDeletionPort {

        private final RecordingPosts posts;
        private final List<Long> deletedIds = new ArrayList<>();

        RecordingPostDeletion(RecordingPosts posts) {
            this.posts = posts;
        }

        @Override
        public void deleteAsAdmin(long postId) {
            deletedIds.add(postId);
            posts.deleteById(postId);
        }
    }
}
