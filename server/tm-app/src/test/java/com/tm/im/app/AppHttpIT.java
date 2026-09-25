package com.tm.im.app;

import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.enums.SecretType;
import com.tm.im.domain.repository.ActorRepository;
import com.tm.im.domain.repository.ActorSecretRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户端应用的启动与鉴权链路的端到端验证 —— <b>用真实的 HTTP + 真实的 MySQL/Redis</b>。
 *
 * <p><b>为什么必须有这么一个测试</b>：在它之前，本仓库所有的验证都跑在测试自己的
 * 容器里（{@code ItSpringConfig} 手工装配数据源、{@code MockMvc} 手工装配 MVC），
 * 而<b>生产的那套装配从未被启动过</b>。这类偏差是本项目最难发现的一类：
 * 测试全绿而 {@code java -jar} 起不来，或者起来了但少了一半 Bean。
 * {@link SpringBootTest} 用 {@code TmAppApplication} 真正启动一遍
 * （Tomcat + Netty + ShardingSphere + Redis + MyBatis-Plus + 全部配置类），
 * 于是「配置文件写错」「扫描范围漏了」「Bean 循环依赖」都会在这里显形。
 *
 * <p><b>它同时是「{@code application.yml} 里的配置真的生效了吗」的唯一证据</b>：
 * snake_case 字段命名、时间格式、未映射路由的 404 处理都在这里用真实 HTTP 断言。
 * MockMvc 那套测不到它们——那里的 Jackson 是测试自己就地构造的。
 *
 * <p><b>数据卫生</b>：handle 带随机后缀（避免与上一次运行的残留撞车），
 * refresh_token 的 TTL 被配置成 5 分钟（残留会自己过期），
 * 并在 {@link #cleanUp()} 里按 handle 精确删除自己造的行与键。
 * 若进程被强杀，{@code tools/clean_it_leftovers.py} 能按 {@code it_} 前缀再清一次。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppHttpIT {

    private static final String JWT_SECRET = "it-jwt-secret-0123456789abcdef-32B";

    /**
     * 每次运行的唯一后缀。不加它的话第二次运行会撞 {@code uk_handle}，
     * 而报错是 40005（handle 已被占用）——看起来像功能坏了，其实是测试数据没清干净。
     */
    private static final String SUFFIX = Long.toHexString(System.nanoTime() & 0xFFFFFF);
    private static final String HANDLE = "it_app_" + SUFFIX;
    private static final String PASSWORD = "it-pass-12345678";

    @LocalServerPort
    int httpPort;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ActorRepository actors;

    @Autowired
    ActorSecretRepository secrets;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JdbcTemplate jdbc;

    /**
     * 让 {@code exchange} 返回 {@code Map<String,Object>} 而不是裸 {@code Map}。
     * 用 {@code Map.class} 的话类型推断会失败（{@code ResponseEntity<Map>} 不能赋给
     * {@code ResponseEntity<Map<String,Object>>}），而显式强制转换会把一个编译期的
     * 类型问题推迟到运行期的 ClassCastException。
     */
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    private Long actorId;
    private final Set<String> issuedRefreshTokens = new java.util.HashSet<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // 与生产同构：走 ShardingSphere 驱动 + absolutepath 加载器
        registry.add("spring.datasource.url",
                () -> "jdbc:shardingsphere:absolutepath:" + com.tm.im.storage.it.ItEnv.shardingConfig());
        registry.add("tm.identity.jwt-secret", () -> JWT_SECRET);
        registry.add("tm.time.zone", () -> "Asia/Shanghai");

        registry.add("spring.data.redis.host", () -> com.tm.im.storage.it.ItEnv.get("redis.host"));
        registry.add("spring.data.redis.port", () -> com.tm.im.storage.it.ItEnv.get("redis.port"));
        registry.add("spring.data.redis.password",
                () -> com.tm.im.storage.it.ItEnv.getOrEmpty("redis.password"));
        registry.add("spring.data.redis.database", () -> com.tm.im.storage.it.ItEnv.get("redis.db"));

        // 残留自净：
        //   refresh-token-ttl 调到 5 分钟 → 测试造出来的会话键过一会儿自己消失；
        //   node.id 用 it- 前缀 → tools/clean_it_leftovers.py 认得它。
        registry.add("tm.identity.refresh-token-ttl", () -> "5m");
        registry.add("tm.node.id", () -> "it-app-boot");
        registry.add("tm.node.ttl", () -> "5m");
        // 端口 0 = 系统分配，避免与本机已经在跑的服务撞端口
        registry.add("tm.netty.port", () -> "0");
    }

    @AfterAll
    void cleanUp() {
        // 清理以 **handle** 为准，而不是以字段 {@link #actorId} 为准：
        // 它可能从未被赋值（比如「注册成功但断言先挂了」——那时响应里的 id
        // 还没取出来），而按 id 删就会什么也不删。实际就发生过一次：
        // actor 行被下面的按 handle 删除带走了，而 actor_secret 行留下成了孤儿。
        // 「一半靠字段、一半靠 handle」的清理比不清理更难查。
        Actor existing = actors.findByHandle(HANDLE).orElse(null);
        if (existing != null) {
            secrets.delete(existing.getId(), SecretType.PASSWORD_HASH);
        }
        // actor 表是非分片表（靠 !SINGLE 的 *.* 登记），所以这条 DELETE 是单表删除，
        // 不会像 message 那样在 16 张物理表上各删一遍。
        jdbc.update("DELETE FROM actor WHERE handle = ?", HANDLE);

        // refresh 会话：TTL 是 5 分钟（见 properties），即使这里没删掉也会自己消失。
        for (String token : issuedRefreshTokens) {
            redis.delete("tm:rt:" + com.tm.im.common.crypto.RefreshTokens.hash(token));
        }
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("启动即健康：Tomcat 与 Netty 都真的在监听")
    void applicationStarts() {
        // 能跑到这里本身就说明上下文装配完成了（NettyServer 是 SmartLifecycle，
        // 端口绑定失败会让整个上下文启动失败）。这里只再确认一次 HTTP 端口可用，
        // 以及 Redis 真的连上了——两者都是「启动了但依赖没接上」的典型失败点。
        assertThat(httpPort).isPositive();
        assertThat(redis.getConnectionFactory()).isNotNull();
    }

    @Test
    @DisplayName("注册 → /v1/me → 登录 → 刷新 → 登出：全链路的 HTTP 契约")
    void fullAuthRoundTrip() {
        // ---------- 注册 ----------
        Map<String, Object> registered = ok(post("/v1/auth/register",
                Map.of("handle", HANDLE, "password", PASSWORD, "display_name", "IT 用户"), null));
        assertSnakeCase(registered, "actor_id", "handle", "access_token", "refresh_token",
                "expires_in");
        actorId = number(registered.get("actor_id"));
        String accessToken = (String) registered.get("access_token");
        String refreshToken = (String) registered.get("refresh_token");
        issuedRefreshTokens.add(refreshToken);

        assertThat(registered.get("handle")).isEqualTo(HANDLE);
        // 与 02-auth.md §2.1 的 expires_in: 7200 一致
        assertThat(number(registered.get("expires_in"))).isEqualTo(7200L);

        // ---------- 口令确实以慢哈希落库，明文没有落到任何地方 ----------
        String stored = secrets.find(actorId, SecretType.PASSWORD_HASH).orElseThrow()
                .getSecretHash();
        assertThat(stored).startsWith("pbkdf2-sha256$").doesNotContain(PASSWORD);

        // ---------- /v1/me ----------
        Map<String, Object> me = ok(get("/v1/me", accessToken));
        assertSnakeCase(me, "actor_id", "actor_type", "handle", "display_name", "status", "created_at");
        assertThat(me.get("handle")).isEqualTo(HANDLE);
        assertThat(me.get("display_name")).isEqualTo("IT 用户");
        assertThat(number(me.get("actor_type"))).isEqualTo(1L);   // HUMAN
        assertThat(number(me.get("status"))).isEqualTo(1L);       // ACTIVE
        assertThat(me.get("avatar_url")).isNull();               // 03-rest-api §2.1 的示例里就是 null
        // 时间必须是带 Z 的 UTC 毫秒（03-rest-api.md §1.6）。
        // 这里断言它能被解析成 Instant 而不是 LocalDateTime 那种「没有时区的墙上时间」。
        Instant createdAt = Instant.parse((String) me.get("created_at"));
        assertThat(createdAt).isBefore(Instant.now().plusSeconds(60));

        // ---------- 登录用错了口令 ----------
        ResponseEntity<Map<String, Object>> badLogin = post("/v1/auth/login",
                Map.of("handle", HANDLE, "password", "wrong-password-1234"), null);
        assertThat(badLogin.getStatusCode().value()).isEqualTo(401);
        assertThat(number(body(badLogin).get("code"))).isEqualTo(40101L);

        // ---------- 登录 ----------
        Map<String, Object> loggedIn = ok(post("/v1/auth/login",
                Map.of("handle", HANDLE, "password", PASSWORD), null));
        String loginRefresh = (String) loggedIn.get("refresh_token");
        issuedRefreshTokens.add(loginRefresh);
        assertThat(number(loggedIn.get("actor_id"))).isEqualTo(actorId);

        // ---------- 刷新：一次性、会轮换 ----------
        Map<String, Object> refreshed = ok(post("/v1/auth/refresh",
                Map.of("refresh_token", loginRefresh), null));
        String rotated = (String) refreshed.get("refresh_token");
        issuedRefreshTokens.add(rotated);
        assertThat(rotated).isNotEqualTo(loginRefresh);

        // 旧凭证再用 → 40104（02-auth.md §2.3 的「一次性」）
        ResponseEntity<Map<String, Object>> replay = post("/v1/auth/refresh",
                Map.of("refresh_token", loginRefresh), null);
        assertThat(replay.getStatusCode().value()).isEqualTo(401);
        assertThat(number(body(replay).get("code"))).isEqualTo(40104L);

        // ---------- 登出 ----------
        // 登出没有 data（02-auth.md §2.4 只关心是否成功），所以用不带 data 断言的助手
        okNoData(post("/v1/auth/logout", Map.of("refresh_token", rotated), accessToken));
        ResponseEntity<Map<String, Object>> afterLogout = post("/v1/auth/refresh",
                Map.of("refresh_token", rotated), null);
        assertThat(number(body(afterLogout).get("code"))).isEqualTo(40104L);
    }

    @Test
    @DisplayName("鉴权失败的三条路径各有各的错误码（40101 / 40102 / 40103）")
    void authenticationFailuresUseDistinctCodes() {
        ResponseEntity<Map<String, Object>> missing = get("/v1/me", null);
        assertThat(missing.getStatusCode().value()).isEqualTo(401);
        assertThat(number(body(missing).get("code"))).isEqualTo(40101L);

        ResponseEntity<Map<String, Object>> malformed = get("/v1/me", "not-a-jwt");
        assertThat(malformed.getStatusCode().value()).isEqualTo(401);
        // 「不是 Bearer 形式」与「Bearer 里不是合法凭证」是两件事：
        // 前者是拼装代码错，后者要触发刷新。这里走的是后者（JWT 验签失败 → 40102）
        assertThat(number(body(malformed).get("code"))).isEqualTo(40102L);

        // api_key 形态（sk_ 开头）但不存在 → 40105，不是 40102：
        // 客户端据此知道该去轮换密钥而不是重新登录
        ResponseEntity<Map<String, Object>> badApiKey = get("/v1/me", "sk_live_does_not_exist");
        assertThat(number(body(badApiKey).get("code"))).isEqualTo(40105L);
    }

    @Test
    @DisplayName("未映射路由：HTTP 404 + code 40400（统一信封，不是 Spring 默认的错误 JSON）")
    void unknownRouteUsesTheEnvelope() {
        ResponseEntity<Map<String, Object>> r = get("/v1/no-such-endpoint", null);

        assertThat(r.getStatusCode().value()).isEqualTo(404);
        assertThat(number(body(r).get("code"))).isEqualTo(40400L);
        assertThat(body(r).get("message")).isEqualTo("not found");
    }

    @Test
    @DisplayName("请求体不是合法 JSON：HTTP 200 + 40000（业务失败，不是路由/协议错）")
    void malformedJsonIsBusinessFailure() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> r = rest.exchange(url("/v1/auth/login"),
                HttpMethod.POST, new HttpEntity<>("{\"handle\": ", headers), JSON_MAP);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(number(body(r).get("code"))).isEqualTo(40000L);
    }

    @Test
    @DisplayName("请求体缺字段：HTTP 200 + 40001（与 40000 的区别是「有 JSON，但少东西」）")
    void missingFieldsAreReported() {
        ResponseEntity<Map<String, Object>> r = post("/v1/auth/login", Map.of(), null);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(number(body(r).get("code"))).isEqualTo(40001L);
    }

    @Test
    @DisplayName("handle 格式非法：HTTP 200 + 40004（文档 §2.1：3-32 位字母数字下划线）")
    void invalidHandleIsReported() {
        ResponseEntity<Map<String, Object>> r = post("/v1/auth/register",
                Map.of("handle", "it-app-非法", "password", PASSWORD), null);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(number(body(r).get("code"))).isEqualTo(40004L);
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 断言信封里的字段名是 snake_case。
     *
     * <p>这条断言在别处测不到：它取决于 {@code spring.jackson.property-naming-strategy}
     * 是否真的被 Boot 读到了——那个设置写错时不会报错，只会让所有字段名悄悄变成
     * camelCase，而客户端按文档解析就得到一片 null。
     */
    private static void assertSnakeCase(Map<String, Object> data, String... expectedKeys) {
        assertThat(data.keySet()).contains(expectedKeys);
        for (String key : expectedKeys) {
            if (!key.contains("_")) {
                // 单词字段（handle / status）没有两种写法，比不出东西来
                continue;
            }
            String camel = toCamel(key);
            assertThat(data.keySet()).as("不应出现 camelCase 字段 %s", camel).doesNotContain(camel);
        }
    }

    private static String toCamel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }

    private ResponseEntity<Map<String, Object>> get(String path, String bearer) {
        return rest.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(headers(bearer)), JSON_MAP);
    }

    private ResponseEntity<Map<String, Object>> post(String path, Object body, String bearer) {
        HttpHeaders headers = headers(bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(com.tm.im.common.json.Json.write(body), headers), JSON_MAP);
    }

    private HttpHeaders headers(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.set("Authorization", "Bearer " + bearer);
        }
        headers.set("X-TM-Device-Id", "it-junit");
        return headers;
    }

    private String url(String path) {
        return "http://127.0.0.1:" + httpPort + path;
    }

    /**
     * 断言「成功且没有 data」。
     *
     * <p>不能用 {@link #ok}：它对 {@code data} 判非空，而登出这类接口按文档
     * 就没有返回数据。用错助手会得到一个「data 是 null」的失败——
     * 看起来像接口没实现，其实是断言用错了地方。
     */
    private static void okNoData(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode().value()).as("期望成功，实际 %s", response.getBody())
                .isEqualTo(200);
        assertThat(number(body(response).get("code"))).isEqualTo(0L);
    }

    /** 取信封里的 {@code data}（成功路径专用）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> ok(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode().value()).as("期望成功，实际 %s", response.getBody())
                .isEqualTo(200);
        Map<String, Object> envelope = body(response);
        assertThat(number(envelope.get("code"))).as("期望 code=0，实际 %s", envelope)
                .isEqualTo(0L);
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        assertThat(data).isNotNull();
        return data;
    }

    private static Map<String, Object> body(ResponseEntity<Map<String, Object>> response) {
        Map<String, Object> body = response.getBody();
        assertThat(body).as("响应体不是 JSON 对象").isNotNull();
        return new LinkedHashMap<>(body);
    }

    private static long number(Object value) {
        assertThat(value).as("期望数字字段，实际 %s", value).isInstanceOf(Number.class);
        return ((Number) value).longValue();
    }
}

