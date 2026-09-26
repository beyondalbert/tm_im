package com.tm.im.app;

import com.tm.im.domain.enums.SecretType;
import com.tm.im.domain.repository.ActorRepository;
import com.tm.im.domain.repository.ActorSecretRepository;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 管理的端到端验证（03-rest-api.md §7），也就是 M8 的验收标准：
 * <b>「把用户端 H5 的调用换成 Agent SDK，同一业务流程只改认证头即可跑通」</b>。
 *
 * <p>本类的 {@link #equivalenceAcceptance()} 把这句话变成了一条可执行的断言：
 * 人类注册 → 创建 Agent（拿 api_key）→ <b>Agent 用 api_key 发好友请求</b> →
 * 人类同意 → <b>Agent 用 api_key 发消息</b> → 人类读到了那条消息。
 * 中间没有任何「Agent 专用接口」——每一步用的都是 §3/§4 里那两个人类也在用的路由，
 * 唯一的差别是 {@code Authorization} 头里放的是 api_key 而不是 JWT。
 *
 * <p>其余用例覆盖那些「只有真实 HTTP 才看得见」的地方：响应里的
 * {@code api_key}/{@code webhook_secret} 是否真的出现（且只在创建那一次）、
 * 40302 是不是 HTTP 403、停用之后 api_key 是 40301 而不是 40105。
 *
 * <p>这里用了 {@code PATCH}（改配置），所以请求工厂换成 JDK {@code HttpClient}
 * ——{@code TestRestTemplate} 默认那套底层是 {@code HttpURLConnection}，
 * 它不认识 PATCH（见 {@code ConversationMemberHttpIT} 的说明）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentHttpIT {

    private static final String JWT_SECRET = "it-jwt-secret-0123456789abcdef-32B";
    private static final String PASSWORD = "it-pass-12345678";
    private static final String SUFFIX = Long.toHexString(System.nanoTime() & 0xFFFFFF);

    private static final String OWNER = "it_ag_owner_" + SUFFIX;
    private static final String OTHER = "it_ag_other_" + SUFFIX;

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

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    private final List<Long> agentIds = new ArrayList<>();
    private final List<Long> conversations = new ArrayList<>();
    private final Set<String> refreshTokens = new java.util.HashSet<>();

    private Account owner;
    private Account other;

    private record Account(long actorId, String handle, String token) {
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:shardingsphere:absolutepath:" + com.tm.im.storage.it.ItEnv.shardingConfig());
        registry.add("tm.identity.jwt-secret", () -> JWT_SECRET);
        registry.add("tm.time.zone", () -> "Asia/Shanghai");
        registry.add("spring.data.redis.host", () -> com.tm.im.storage.it.ItEnv.get("redis.host"));
        registry.add("spring.data.redis.port", () -> com.tm.im.storage.it.ItEnv.get("redis.port"));
        registry.add("spring.data.redis.password",
                () -> com.tm.im.storage.it.ItEnv.getOrEmpty("redis.password"));
        registry.add("spring.data.redis.database", () -> com.tm.im.storage.it.ItEnv.get("redis.db"));
        registry.add("tm.identity.refresh-token-ttl", () -> "5m");
        registry.add("tm.node.id", () -> "it-agent-boot");
        registry.add("tm.node.ttl", () -> "5m");
        registry.add("tm.netty.port", () -> "0");
    }

    @BeforeAll
    void setUp() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        owner = newAccount(OWNER);
        other = newAccount(OTHER);
    }

    @AfterAll
    void cleanUp() {
        for (long convId : conversations) {
            jdbc.update("DELETE FROM conversation_member WHERE conv_id = ?", convId);
            jdbc.update("DELETE FROM message WHERE conv_id = ?", convId);
            jdbc.update("DELETE FROM conversation WHERE id = ?", convId);
            redis.delete("tm:seq:" + convId);
        }
        for (long handleId : List.of(owner.actorId(), other.actorId())) {
            jdbc.update("DELETE FROM friendship WHERE actor_a = ? OR actor_b = ?", handleId, handleId);
        }
        for (Long agentId : agentIds) {
            jdbc.update("DELETE FROM friendship WHERE actor_a = ? OR actor_b = ?", agentId, agentId);
            jdbc.update("DELETE FROM agent_profile WHERE actor_id = ?", agentId);
            jdbc.update("DELETE FROM actor_secret WHERE actor_id = ?", agentId);
            jdbc.update("DELETE FROM actor WHERE id = ?", agentId);
        }
        for (Account account : List.of(owner, other)) {
            actors.findByHandle(account.handle()).ifPresent(actor -> {
                secrets.delete(actor.getId(), SecretType.PASSWORD_HASH);
                jdbc.update("DELETE FROM actor WHERE id = ?", actor.getId());
            });
        }
        for (String token : refreshTokens) {
            redis.delete("tm:rt:" + com.tm.im.common.crypto.RefreshTokens.hash(token));
        }
    }

    // ================================================================ 对等性验收

    @Test
    @DisplayName("对等性验收：Agent 用 api_key 走完「加好友 → 被同意 → 发消息」全程，人类读到了那条消息")
    void equivalenceAcceptance() {
        Map<String, Object> created = ok(post("/v1/agents", Map.of(
                "handle", "it_bot_" + SUFFIX,
                "display_name", "天气助手",
                "bio", "提供全球天气查询",
                "push_mode", 1,
                "endpoint_url", "https://agent.example.com/tm/callback",
                "capabilities", List.of("text", "image")), owner.token()));
        assertSnakeCase(created, "actor_id", "handle", "actor_type", "push_mode", "api_key",
                "webhook_secret", "created_at");
        long agentId = number(created.get("actor_id"));
        agentIds.add(agentId);
        String apiKey = (String) created.get("api_key");
        assertThat(apiKey).startsWith("sk_live_");
        assertThat((String) created.get("webhook_secret")).startsWith("whsec_");
        assertThat(number(created.get("actor_type"))).as("actor_type=2（AGENT），只有这一处不同").isEqualTo(2L);

        // ---------- 用 api_key 调 /v1/me：同一个接口，只有认证头不同 ----------
        Map<String, Object> me = ok(get("/v1/me", apiKey));
        assertThat(number(me.get("actor_id"))).isEqualTo(agentId);
        assertThat(number(me.get("actor_type"))).isEqualTo(2L);

        // ---------- Agent 发好友请求（§3.1，人类也在用的那条路由） ----------
        Map<String, Object> requested = ok(post("/v1/friends/requests",
                Map.of("target", "@" + OWNER, "message", "我是天气助手"), apiKey));
        long requestId = number(requested.get("request_id"));

        // ---------- 人类侧看到它、同意（同样的 §3.2/§3.3） ----------
        List<Object> incoming = list(ok(get("/v1/friends/requests?direction=incoming", owner.token()))
                .get("items"));
        assertThat(incoming).hasSize(1);
        assertThat(number(map(map(incoming.get(0)).get("from_actor")).get("actor_id"))).isEqualTo(agentId);

        Map<String, Object> accepted = ok(post("/v1/friends/requests/" + requestId + "/accept",
                Map.of(), owner.token()));
        long convId = number(accepted.get("conv_id"));
        conversations.add(convId);

        // ---------- Agent 发消息（§4.5），人类读到了 ----------
        Map<String, Object> sent = ok(post("/v1/conversations/" + convId + "/messages",
                Map.of("msg_type", "TEXT", "client_msg_id", "it-ag-1",
                        "content", Map.of("text", "北京今天晴")), apiKey));
        assertThat(number(sent.get("seq"))).isEqualTo(1L);

        List<Object> history = list(ok(get("/v1/conversations/" + convId + "/messages",
                owner.token())).get("items"));
        assertThat(history).hasSize(1);
        Map<String, Object> message = map(history.get(0));
        assertThat(number(message.get("sender_id"))).isEqualTo(agentId);
        assertThat(map(message.get("content")).get("text")).isEqualTo("北京今天晴");

        // ---------- 反向：人类发给 Agent（同一会话） ----------
        ok(post("/v1/conversations/" + convId + "/messages",
                Map.of("msg_type", "TEXT", "client_msg_id", "it-ag-2",
                        "content", Map.of("text", "收到")), owner.token()));
        List<Object> agentView = list(ok(get("/v1/conversations/" + convId + "/messages",
                apiKey)).get("items"));
        assertThat(agentView).as("Agent 读同一个接口，拿到两条").hasSize(2);

        // 清理：把这条会话留给 AfterAll
    }

    // ================================================================ 管理接口

    @Test
    @DisplayName("列表/详情/改配置：只有拥有者能做（40302 → HTTP 403）")
    void managementIsOwnerOnly() {
        long agentId = number(ok(post("/v1/agents", Map.of(
                "handle", "it_mgmt_bot_" + SUFFIX, "push_mode", 3), owner.token())).get("actor_id"));
        agentIds.add(agentId);

        assertThat(okList(get("/v1/agents", owner.token())))
                .as("只有拥有者的列表里才有它")
                .isNotEmpty();

        ResponseEntity<Map<String, Object>> denied = get("/v1/agents/" + agentId, other.token());
        assertThat(denied.getStatusCode().value()).as("40302 属于权限段，HTTP 是 403").isEqualTo(403);
        assertThat(number(body(denied).get("code"))).isEqualTo(40302L);

        // PATCH：只改昵称，其它字段不动
        Map<String, Object> patched = ok(patch("/v1/agents/" + agentId,
                Map.of("display_name", "改过的名字"), owner.token()));
        assertThat(patched.get("display_name")).isEqualTo("改过的名字");
        assertThat(number(patched.get("push_mode"))).as("未提供的字段不改").isEqualTo(3L);

        // 参数：push_mode 越界 40002、缺失 40001、handle 重复 40005
        assertThat(number(body(post("/v1/agents",
                Map.of("handle", "it_bad_mode_" + SUFFIX, "push_mode", 9), owner.token())
        ).get("code"))).isEqualTo(40002L);
        assertThat(number(body(post("/v1/agents",
                Map.of("handle", "it_no_mode_" + SUFFIX), owner.token())).get("code")))
                .isEqualTo(40001L);
        assertThat(number(body(post("/v1/agents",
                Map.of("handle", "it_mgmt_bot_" + SUFFIX, "push_mode", 3), owner.token())
        ).get("code"))).isEqualTo(40005L);
        assertThat(number(body(post("/v1/agents",
                Map.of("handle", "ab", "push_mode", 3), owner.token())).get("code")))
                .isEqualTo(40004L);
    }

    @Test
    @DisplayName("轮换 api_key：旧的立即 40105，新的可用；停用后 api_key 得到 40301")
    void rotateAndDisable() {
        long agentId = number(ok(post("/v1/agents", Map.of(
                "handle", "it_rot_bot_" + SUFFIX, "push_mode", 3), owner.token())).get("actor_id"));
        agentIds.add(agentId);
        // 老 key：先轮换一次拿到确定性的一把
        String firstKey = (String) ok(post("/v1/agents/" + agentId + "/rotate-key",
                Map.of(), owner.token())).get("api_key");
        assertThat(ok(get("/v1/me", firstKey)).get("handle")).isEqualTo("it_rot_bot_" + SUFFIX);

        Map<String, Object> rotated = ok(post("/v1/agents/" + agentId + "/rotate-key",
                Map.of(), owner.token()));
        String secondKey = (String) rotated.get("api_key");
        assertThat(secondKey).isNotEqualTo(firstKey);
        assertThat(number(rotated.get("actor_id"))).isEqualTo(agentId);

        ResponseEntity<Map<String, Object>> oldKey = get("/v1/me", firstKey);
        assertThat(oldKey.getStatusCode().value()).as("旧 key 立即失效 → 401").isEqualTo(401);
        assertThat(number(body(oldKey).get("code"))).isEqualTo(40105L);
        assertThat(ok(get("/v1/me", secondKey)).get("handle")).isEqualTo("it_rot_bot_" + SUFFIX);

        // 停用：api_key 仍在表里（哈希），所以鉴权能走到「账号已停用」那一步
        Map<String, Object> disabled = ok(remove("/v1/agents/" + agentId, owner.token()));
        assertThat(number(disabled.get("status"))).as("status=2（SUSPENDED）").isEqualTo(2L);

        ResponseEntity<Map<String, Object>> afterDisable = get("/v1/me", secondKey);
        assertThat(afterDisable.getStatusCode().value()).isEqualTo(403);
        assertThat(number(body(afterDisable).get("code")))
                .as("40301 的客户端动作是「停止重试」；回 40105 会让 Agent 一直去轮换密钥——而它无权轮换")
                .isEqualTo(40301L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM actor_secret WHERE actor_id = ?"
                        + " AND secret_type = 2", Long.class, agentId))
                .as("api_key 的哈希被刻意保留")
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM actor_secret WHERE actor_id = ?"
                        + " AND secret_type = 3", Long.class, agentId))
                .as("webhook_secret 被删掉（停用后不该再签出任何回调）")
                .isZero();
    }

    // ================================================================ 工具

    private Account newAccount(String handle) {
        Map<String, Object> data = ok(post("/v1/auth/register",
                Map.of("handle", handle, "password", PASSWORD, "display_name", handle), null));
        refreshTokens.add((String) data.get("refresh_token"));
        return new Account(number(data.get("actor_id")), handle, (String) data.get("access_token"));
    }

    /** GET，返回原始响应（本类要断言失败路径的状态码，所以它与 {@code ok} 并存）。 */
    private ResponseEntity<Map<String, Object>> get(String path, String bearer) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers(bearer)), JSON_MAP);
    }

    /** 成功路径的 GET（取 {@code data}，且它是数组）。列表接口的 data 是数组而不是对象。 */
    private static List<Object> okList(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode().value()).as("期望成功，实际 %s", response.getBody())
                .isEqualTo(200);
        Map<String, Object> envelope = body(response);
        assertThat(number(envelope.get("code"))).as("期望 code=0，实际 %s", envelope).isZero();
        return list(envelope.get("data"));
    }

    private ResponseEntity<Map<String, Object>> post(String path, Object body, String bearer) {
        HttpHeaders headers = headers(bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(com.tm.im.common.json.Json.write(body), headers), JSON_MAP);
    }

    private ResponseEntity<Map<String, Object>> patch(String path, Object body, String bearer) {
        HttpHeaders headers = headers(bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url(path), HttpMethod.PATCH,
                new HttpEntity<>(com.tm.im.common.json.Json.write(body), headers), JSON_MAP);
    }

    private ResponseEntity<Map<String, Object>> remove(String path, String bearer) {
        return rest.exchange(url(path), HttpMethod.DELETE, new HttpEntity<>(headers(bearer)), JSON_MAP);
    }

    private HttpHeaders headers(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.set("Authorization", "Bearer " + bearer);
        }
        headers.set("X-TM-Device-Id", "it-junit");
        return headers;
    }

    private String url(String path) {
        return "http://127.0.0.1:" + httpPort + path;
    }

    private static void assertSnakeCase(Map<String, Object> data, String... expectedKeys) {
        assertThat(data.keySet()).contains(expectedKeys);
        for (String key : data.keySet()) {
            assertThat(key).as("响应里出现了 camelCase 键（应为 %s）", snake(key)).isEqualTo(snake(key));
        }
    }

    private static String snake(String key) {
        StringBuilder sb = new StringBuilder();
        for (char c : key.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Map<String, Object> ok(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode().value()).as("期望成功，实际 %s", response.getBody())
                .isEqualTo(200);
        Map<String, Object> envelope = body(response);
        assertThat(number(envelope.get("code"))).as("期望 code=0，实际 %s", envelope).isZero();
        return map(envelope.get("data"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).as("期望 JSON 对象，实际 %s", value).isInstanceOf(Map.class);
        return new LinkedHashMap<>((Map<String, Object>) value);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        assertThat(value).as("期望 JSON 数组，实际 %s", value).isInstanceOf(List.class);
        return (List<Object>) value;
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
