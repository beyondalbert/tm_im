package com.tm.im.admin;

import com.tm.im.common.crypto.PasswordHashes;
import com.tm.im.core.admin.AdminService;
import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.entity.AdminUser;
import com.tm.im.domain.entity.Post;
import com.tm.im.domain.enums.ActorStatus;
import com.tm.im.domain.enums.ActorType;
import com.tm.im.domain.enums.AdminRole;
import com.tm.im.domain.enums.AdminStatus;
import com.tm.im.domain.enums.Visibility;
import com.tm.im.domain.repository.ActorRepository;
import com.tm.im.domain.repository.PostRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理后台的端到端验证 —— <b>真实 HTTP + 真实 MySQL</b>（M9 的验收标准：可封禁、可查日志）。
 *
 * <p><b>为什么必须是「真启动」</b>：与 {@code AppHttpIT} 同一理由——本仓库所有的
 * 单元测试都跑在测试自己装配的容器里，而 {@code tm-admin} 是一个<b>独立的 JAR</b>，
 * 它的扫描清单（哪些包进、哪些包不进）只在真启动时才生效。第一个把
 * {@code tm-admin} 跑起来的版本就是因为扫描范围而失败的，而那个失败在
 * MockMvc 里完全看不见。
 *
 * <p><b>它还顺带钉住两条跨模块的性质</b>：
 * <ol>
 *   <li>后台的「封禁」写的是 {@code actor.status}，即用户端鉴权读的<b>同一列</b>
 *       ——所以封禁立刻生效，不需要任何同步机制；</li>
 *   <li>后台删帖走的是作者删帖那条级联路径 ——收件箱/点赞/评论在同一个事务里被清掉。</li>
 * </ol>
 *
 * <p><b>数据卫生</b>：所有行都用 {@code it_adm_} / {@code it_admin_} 前缀，
 * {@link #cleanUp()} 按前缀精确删除；进程被强杀时 {@code tools/clean_it_leftovers.py}
 * 能再清一次（它认这个前缀）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminHttpIT {

    private static final String SUFFIX = Long.toHexString(System.nanoTime() & 0xFFFFFF);
    private static final String ADMIN_USERNAME = "it_admin_" + SUFFIX;
    private static final String OPS_USERNAME = "it_ops_" + SUFFIX;
    /**
     * 专门用来「被停用」的账号。
     *
     * <p>存在的理由是被测试自己拓出来的：停用是一次<b>持久的</b>副作用，
     * 而 JUnit 不保证方法顺序——第一个版本里 {@code disablingAdminKillsSessions}
     * 停用了 OPS 账号，之后任何一个用 OPS 登录的用例都会拿到 40301。
     * 共享夹具的用例不该互相改变对方的可用状态，所以「会被改坏的那个」单独有一个账号。
     */
    private static final String VICTIM_USERNAME = "it_victim_" + SUFFIX;
    /** 专门用来「连续登录失败」的账号：失败计数会清零，同样是一个会被改坏的状态。 */
    private static final String FAILURE_USERNAME = "it_fail_" + SUFFIX;
    private static final String PASSWORD = "it-admin-password-1";
    private static final String ACTOR_HANDLE = "it_adm_" + SUFFIX;

    @LocalServerPort
    int httpPort;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    AdminService admins;

    @Autowired
    ActorRepository actors;

    @Autowired
    PostRepository posts;

    @Autowired
    JdbcTemplate jdbc;

    private String token;
    private long superAdminId;
    private long actorId;
    private long postId;

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // 与生产同构：ShardingSphere 驱动 + absolutepath 加载器
        registry.add("spring.datasource.url",
                () -> "jdbc:shardingsphere:absolutepath:"
                        + com.tm.im.storage.it.ItEnv.shardingConfig());
        registry.add("tm.time.zone", () -> "Asia/Shanghai");
        // node.id 用 it- 前缀 → clean_it_leftovers.py 认得它；Snowflake 节点号
        // 与 tm-app 的集成测试分开，避免两边同时跑时撞号。
        registry.add("tm.node.id", () -> "it-admin-boot");
        // 首个账号的 bootstrap 配置**故意不配**：本测试自己建号（见 setUp），
        // 这样「表里已有账号时 bootstrap 是空操作」这条性质也能顺带被断言。
        registry.add("tm.admin.session-ttl", () -> "5m");
    }

    @BeforeAll
    void setUp() {
        // 上一个用例可能留下同前缀的行（同一台机器上重复跑）——先按前缀清干净，
        // 否则 uk_username 会让本类的建号直接失败。
        jdbc.update("DELETE FROM admin_user WHERE username LIKE 'it_admin_%' "
                + "OR username LIKE 'it_ops_%' OR username LIKE 'it_victim_%' "
                + "OR username LIKE 'it_fail_%'");
        jdbc.update("DELETE FROM admin_session WHERE admin_id IN "
                + "(SELECT id FROM admin_user WHERE username LIKE 'it_%')");

        superAdminId = insertAdmin(ADMIN_USERNAME, AdminRole.SUPER);
        insertAdmin(OPS_USERNAME, AdminRole.OPS);
        insertAdmin(VICTIM_USERNAME, AdminRole.OPS);
        insertAdmin(FAILURE_USERNAME, AdminRole.OPS);

        Actor actor = new Actor();
        actor.setId(System.nanoTime() & 0x7FFFFFFFFFFL);
        actor.setActorType(ActorType.HUMAN);
        actor.setHandle(ACTOR_HANDLE);
        actor.setDisplayName("IT 被封的人");
        actor.setStatus(ActorStatus.ACTIVE);
        actor.setCreatedAt(LocalDateTime.now());
        actors.insert(actor);
        actorId = actor.getId();

        Post post = new Post();
        post.setId(actorId + 1);
        post.setAuthorId(actorId);
        post.setContent("{\"text\":\"it adm post\"}");
        post.setVisibility(Visibility.PUBLIC);
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setCreatedAt(LocalDateTime.now());
        posts.insert(post);
        postId = post.getId();

        token = login(ADMIN_USERNAME, PASSWORD);
    }

    @AfterAll
    void cleanUp() {
        // 先删「引用了别人」的行，再删被引用的行：审计与会话都挂着 admin_id，
        // 而与库里的外键无关——这里只是为了让前缀删除可重复执行。
        jdbc.update("DELETE FROM admin_session WHERE admin_id IN "
                + "(SELECT id FROM admin_user WHERE username LIKE 'it_%')");
        jdbc.update("DELETE FROM admin_audit_log WHERE admin_name LIKE 'it_%' "
                + "OR target_id IN (?, ?)", actorId, postId);
        jdbc.update("DELETE FROM admin_user WHERE username LIKE 'it_%'");
        jdbc.update("DELETE FROM feed_item WHERE post_id = ?", postId);
        jdbc.update("DELETE FROM post_like WHERE post_id = ?", postId);
        jdbc.update("DELETE FROM post_comment WHERE post_id = ?", postId);
        jdbc.update("DELETE FROM post WHERE id = ?", postId);
        jdbc.update("DELETE FROM actor WHERE handle = ?", ACTOR_HANDLE);
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("启动即健康：管理后台独立起在随机端口上（不依赖 tm-app 的任何 Bean）")
    void applicationStarts() {
        assertThat(httpPort).isPositive();
        // 扫描清单里没有 core.identity，所以这个进程**没有** JWT 密钥也能起来：
        // 若哪天有人把 identity 的包加进扫描范围，这里会以「jwt secret 未配置」失败。
        Map<String, Object> body = get("/v1/admin/me", token).getBody();
        assertThat(body).isNotNull();
        assertThat(((Map<?, ?>) body.get("data")).get("username")).isEqualTo(ADMIN_USERNAME);
    }

    @Test
    @DisplayName("登录 → 会话 → /v1/admin/me：snake_case 与时间格式都真的生效")
    void loginAndMe() {
        ResponseEntity<Map<String, Object>> response = post("/v1/admin/auth/login",
                Map.of("username", ADMIN_USERNAME, "password", PASSWORD), null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> data = dataOf(response);
        assertThat(data.get("token").toString()).startsWith("adm_");
        assertThat(data.get("expires_at").toString()).endsWith("Z");
        Map<?, ?> admin = (Map<?, ?>) data.get("admin");
        assertThat(admin.get("username")).isEqualTo(ADMIN_USERNAME);
        assertThat(admin.get("role")).isEqualTo(1);
        // 响应里不允许出现任何口令痕迹
        assertThat(data.toString()).doesNotContain("pbkdf2").doesNotContain("password");
    }

    @Test
    @DisplayName("鉴权：没有头回 40101；贴 JWT / api_key 回 40102")
    void authenticationErrors() {
        assertThat(get("/v1/admin/me", null).getStatusCode().value()).isEqualTo(401);
        assertThat(get("/v1/admin/me", "eyJhbGciOiJIUzI1NiJ9.e30.x").getStatusCode().value())
                .isEqualTo(401);
        assertThat(get("/v1/admin/me", "sk_live_does_not_exist_here").getBody().get("code"))
                .isEqualTo(40102);
    }

    @Test
    @DisplayName("口令错回 40101，审计里留下 ADMIN_LOGIN_FAILED，失败计数落在库里")
    void failedLoginIsAudited() {
        // 用专门的账号：成功登录会把失败计数清零，而两个用例共享一个账号时
        // 「计数 >= 1」这条断言就取决于执行顺序（本条断言曾被那样坑过一次）。
        Long failureAdminId = jdbc.queryForObject(
                "SELECT id FROM admin_user WHERE username = ?", Long.class, FAILURE_USERNAME);
        ResponseEntity<Map<String, Object>> response = post("/v1/admin/auth/login",
                Map.of("username", FAILURE_USERNAME, "password", "wrong-password"), null);

        assertThat(response.getBody().get("code")).isEqualTo(40101);
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_log "
                + "WHERE admin_id = ? AND action = 'ADMIN_LOGIN_FAILED'",
                Integer.class, failureAdminId);
        assertThat(n).isEqualTo(1);
        // 失败计数落在库里（多实例部署时内存计数等于「每个实例各给几次机会」）
        Integer failed = jdbc.queryForObject(
                "SELECT failed_attempts FROM admin_user WHERE id = ?", Integer.class, failureAdminId);
        assertThat(failed).isEqualTo(1);
    }

    @Test
    @DisplayName("封禁：写的是 actor.status（用户端鉴权读的同一列），并能在列表里筛出 SUSPENDED")
    void suspendActorIsVisibleAndQueryable() {
        ResponseEntity<Map<String, Object>> banned = patch(
                "/v1/admin/actors/" + actorId + "/status",
                Map.of("status", 2, "reason", "IT 冒烟"), token);

        assertThat(banned.getStatusCode().value()).isEqualTo(200);
        assertThat(dataOf(banned).get("status")).isEqualTo(2);
        // 直接读库：这一列就是用户端 IdentityService 判 ACTIVE 的那一列
        Integer status = jdbc.queryForObject(
                "SELECT status FROM actor WHERE id = ?", Integer.class, actorId);
        assertThat(status).isEqualTo(2);

        // 列表：按状态筛，且能被 handle 前缀定位到
        Map<String, Object> listed = dataOf(get("/v1/admin/actors?status=2&handle_prefix=it_adm_",
                token));
        List<?> items = (List<?>) listed.get("items");
        assertThat(items).isNotEmpty();

        // 解封之后回到 1
        assertThat(dataOf(patch("/v1/admin/actors/" + actorId + "/status",
                Map.of("status", 1), token)).get("status")).isEqualTo(1);
    }

    @Test
    @DisplayName("封不存在的号回 40401；status 传非法值回 40002（不会静默当成「不过滤」）")
    void suspendValidatesInput() {
        assertThat(get("/v1/admin/actors/999999999/status", token).getBody()).isNotNull();
        assertThat(patch("/v1/admin/actors/" + actorId + "/status",
                Map.of("status", 9), token).getBody().get("code")).isEqualTo(40002);
        assertThat(patch("/v1/admin/actors/999999999/status",
                Map.of("status", 2), token).getBody().get("code")).isEqualTo(40401);
    }

    @Test
    @DisplayName("删帖：级联清掉收件箱 / 点赞 / 评论，且审计记的是这条动态")
    void deletePostCascadesAndIsAudited() {
        jdbc.update("INSERT INTO feed_item (owner_id, score, post_id, author_id) VALUES (?, 1, ?, ?) "
                + "ON DUPLICATE KEY UPDATE score = 1", actorId, postId, actorId);
        jdbc.update("INSERT IGNORE INTO post_like (post_id, actor_id, created_at) VALUES (?, ?, NOW(3))",
                postId, actorId);
        // 列名是 author_id（与 post 一致），不是 actor_id —— 写错会以
        // "Unknown column 'actor_id' in 'field list'" 报出来，而那看起来像分片改写的问题。
        jdbc.update("INSERT INTO post_comment (id, post_id, author_id, content, created_at) "
                        + "VALUES (?, ?, ?, 'it comment', NOW(3))",
                postId + 7, postId, actorId);

        ResponseEntity<Map<String, Object>> deleted = exchange(HttpMethod.DELETE,
                "/v1/admin/posts/" + postId + "?reason=IT", null, token);

        assertThat(deleted.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post WHERE id = ?", Integer.class, postId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM feed_item WHERE post_id = ?",
                Integer.class, postId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_like WHERE post_id = ?",
                Integer.class, postId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_comment WHERE post_id = ?",
                Integer.class, postId)).isZero();

        Integer audits = jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_log "
                + "WHERE action = 'POST_DELETE' AND target_type = 'POST' AND target_id = ? "
                + "AND detail LIKE '%IT%'", Integer.class, postId);
        assertThat(audits).isEqualTo(1);
    }

    @Test
    @DisplayName("审计：可按动作 / 目标 / 管理员三个角度查到刚发生的事，且按时间倒序")
    void auditLogsAreQueryable() {
        // 先由本用例自己制造两条审计（封一次、解一次），这样断言不依赖别的用例
        // 是否跑过、跑在什么顺序——它们用的是同一个参与者。
        patch("/v1/admin/actors/" + actorId + "/status",
                Map.of("status", 2, "reason", "审计用例"), token);
        patch("/v1/admin/actors/" + actorId + "/status", Map.of("status", 1), token);

        Map<String, Object> byTarget = dataOf(get(
                "/v1/admin/audit-logs?target_type=ACTOR&target_id=" + actorId, token));
        List<?> items = (List<?>) byTarget.get("items");
        assertThat(items).hasSizeGreaterThanOrEqualTo(2);
        // 倒序：最后发生的那条在最前（刚才是解封）
        Map<?, ?> first = (Map<?, ?>) items.get(0);
        assertThat(first.get("action")).isEqualTo("ACTOR_STATUS");
        assertThat(first.get("admin_name")).isEqualTo(ADMIN_USERNAME);
        assertThat(first.get("detail").toString()).contains("ACTIVE");

        // 三个角度都可用：按动作、按管理员
        Map<String, Object> byAction = dataOf(get(
                "/v1/admin/audit-logs?action=ACTOR_STATUS&admin_id=" + superAdminId, token));
        assertThat((List<?>) byAction.get("items")).hasSizeGreaterThanOrEqualTo(2);

        Map<String, Object> byUnknownAction = dataOf(get(
                "/v1/admin/audit-logs?action=NO_SUCH_ACTION", token));
        assertThat((List<?>) byUnknownAction.get("items")).isEmpty();
    }

    @Test
    @DisplayName("后台账号管理只有 SUPER 能调：OPS 拿到 40302")
    void opsCannotManageAccounts() {
        String opsToken = login(OPS_USERNAME, PASSWORD);

        assertThat(get("/v1/admin/accounts", opsToken).getBody().get("code")).isEqualTo(40302);
        assertThat(get("/v1/admin/me", opsToken).getStatusCode().value()).isEqualTo(200);

        Map<String, Object> listed = dataOf(get("/v1/admin/accounts", token));
        assertThat((List<?>) listed.get("items")).isNotEmpty();
    }

    @Test
    @DisplayName("停用后台账号会立刻踢掉它的会话（同一个事务），且不能停用自己")
    void disablingAdminKillsSessions() {
        Long victimId = jdbc.queryForObject("SELECT id FROM admin_user WHERE username = ?",
                Long.class, VICTIM_USERNAME);
        String victimToken = login(VICTIM_USERNAME, PASSWORD);
        assertThat(get("/v1/admin/me", victimToken).getStatusCode().value()).isEqualTo(200);

        assertThat(dataOf(patch("/v1/admin/accounts/" + victimId + "/status",
                Map.of("status", 2), token)).get("status")).isEqualTo(2);

        // 旧会话已经不存在了：那正是「停用立刻生效」
        assertThat(get("/v1/admin/me", victimToken).getBody().get("code")).isEqualTo(40102);
        Integer sessions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin_session WHERE admin_id = ?", Integer.class, victimId);
        assertThat(sessions).isZero();
        // 停用之后连登录都进不来（40301 而不是 40101：这是需要运维知道的事实）
        assertThat(post("/v1/admin/auth/login",
                Map.of("username", VICTIM_USERNAME, "password", PASSWORD), null)
                .getBody().get("code")).isEqualTo(40301);

        assertThat(patch("/v1/admin/accounts/" + superAdminId + "/status",
                Map.of("status", 2), token).getBody().get("code")).isEqualTo(40904);

        // 恢复，让夹具可重复使用（下一个用例仍然可以拿 victim 登录）
        assertThat(dataOf(patch("/v1/admin/accounts/" + victimId + "/status",
                Map.of("status", 1), token)).get("status")).isEqualTo(1);
    }

    @Test
    @DisplayName("登出后凭证立即失效，且重复登出仍然成功（幂等）")
    void logoutRevokesImmediately() {
        String temp = login(ADMIN_USERNAME, PASSWORD);

        assertThat(exchange(HttpMethod.POST, "/v1/admin/auth/logout", null, temp)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(get("/v1/admin/me", temp).getBody().get("code")).isEqualTo(40102);
        assertThat(exchange(HttpMethod.POST, "/v1/admin/auth/logout", null, temp)
                .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("bootstrap 在表里已有账号时是空操作（首次启动那条路径由单测覆盖）")
    void bootstrapIsNoOpWhenTableIsNotEmpty() {
        assertThat(admins.bootstrap()).isFalse();
    }

    // ------------------------------------------------------------------ 夹具

    private long insertAdmin(String username, AdminRole role) {
        AdminUser admin = new AdminUser();
        admin.setId(System.nanoTime() & 0x7FFFFFFFFFFL);
        admin.setUsername(username);
        admin.setDisplayName(username);
        admin.setPasswordHash(PasswordHashes.hash(PASSWORD, 20_000));
        admin.setRole(role);
        admin.setStatus(AdminStatus.ACTIVE);
        admin.setFailedAttempts(0);
        admin.setCreatedAt(LocalDateTime.now());
        jdbc.update("INSERT INTO admin_user (id, username, display_name, password_hash, role, "
                        + "status, failed_attempts, created_at) VALUES (?, ?, ?, ?, ?, ?, 0, NOW(3))",
                admin.getId(), admin.getUsername(), admin.getDisplayName(),
                admin.getPasswordHash(), admin.getRole().code(), admin.getStatus().code());
        return admin.getId();
    }

    /** 登录并返回会话明文（{@code adm_} 前缀）。测试用例几乎都以它开头。 */
    private String login(String username, String password) {
        Map<String, Object> data = dataOf(post("/v1/admin/auth/login",
                Map.of("username", username, "password", password), null));
        return data.get("token").toString();
    }

    private ResponseEntity<Map<String, Object>> get(String path, String bearer) {
        return exchange(HttpMethod.GET, path, null, bearer);
    }

    private ResponseEntity<Map<String, Object>> post(String path, Object body, String bearer) {
        return exchange(HttpMethod.POST, path, body, bearer);
    }

    private ResponseEntity<Map<String, Object>> patch(String path, Object body, String bearer) {
        return exchange(HttpMethod.PATCH, path, body, bearer);
    }

    private ResponseEntity<Map<String, Object>> exchange(HttpMethod method, String path,
                                                         Object body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JSON_MAP);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ResponseEntity<Map<String, Object>> response) {
        Map<String, Object> body = response.getBody();
        assertThat(body).as("响应体应当是统一的 ApiResponse").isNotNull();
        Object data = body.get("data");
        assertThat(data).as("失败响应：" + body).isNotNull();
        return (Map<String, Object>) data;
    }
}
