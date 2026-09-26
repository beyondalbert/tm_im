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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 好友链路的端到端验证（03-rest-api.md §3）—— <b>真实 HTTP + 真实 MySQL/Redis</b>。
 *
 * <p><b>为什么必须有它</b>（单测覆盖不到的四件事）：
 * <ol>
 *   <li><b>分页的 SQL 真的对吗</b>：游标谓词带括号、{@code ORDER BY updated_at DESC,
 *       request_id DESC} 与游标键一致、同一毫秒里成为的两个好友不会重复或漏掉。
 *       单测的替身证明不了这些——它是另一份实现；</li>
 *   <li><b>「非好友不能发消息」终于能被端到端地验</b>：此前所有消息类用例都要自己
 *       往 {@code friendship} 里插一行「已是好友」，因为加好友接口还不存在。
 *       现在可以走真实路径：加好友 → 能发 → 删好友 → 40003；</li>
 *   <li>响应的字段名与形状（{@code request_id} / {@code friends_since} / {@code conv_id}）
 *       以及 {@code status=0}（拒绝）这类约定；</li>
 *   <li>错误码对应的 <b>HTTP 状态码</b>：40302 是 403，而 40901/40902/40903/40400 是 200。</li>
 * </ol>
 *
 * <p>数据卫生：handle 带随机后缀，清理时按 handle 删 actor 与 actor_secret，
 * 并按已知的会话 id 删 conversation/member/message 与 {@code tm:seq:*}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FriendHttpIT {

    private static final String JWT_SECRET = "it-jwt-secret-0123456789abcdef-32B";
    private static final String PASSWORD = "it-pass-12345678";
    private static final String SUFFIX = Long.toHexString(System.nanoTime() & 0xFFFFFF);

    private static final String ALICE = "it_fr_alice_" + SUFFIX;

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

    private final List<String> handles = new ArrayList<>();
    private final List<Long> conversations = new ArrayList<>();
    private final Set<String> refreshTokens = new java.util.HashSet<>();
    private final AtomicInteger seq = new AtomicInteger();

    private Account alice;

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
        registry.add("tm.node.id", () -> "it-friend-boot");
        registry.add("tm.node.ttl", () -> "5m");
        registry.add("tm.netty.port", () -> "0");
    }

    @BeforeAll
    void setUp() {
        alice = newAccount(ALICE);
    }

    @AfterAll
    void cleanUp() {
        for (long convId : conversations) {
            jdbc.update("DELETE FROM conversation_member WHERE conv_id = ?", convId);
            jdbc.update("DELETE FROM message WHERE conv_id = ?", convId);
            jdbc.update("DELETE FROM conversation WHERE id = ?", convId);
            redis.delete("tm:seq:" + convId);
        }
        for (String handle : handles) {
            actors.findByHandle(handle).ifPresent(actor -> {
                jdbc.update("DELETE FROM friendship WHERE actor_a = ? OR actor_b = ?",
                        actor.getId(), actor.getId());
                secrets.delete(actor.getId(), SecretType.PASSWORD_HASH);
                jdbc.update("DELETE FROM actor WHERE id = ?", actor.getId());
            });
        }
        for (String token : refreshTokens) {
            redis.delete("tm:rt:" + com.tm.im.common.crypto.RefreshTokens.hash(token));
        }
    }

    // ================================================================ 主链路

    @Test
    @DisplayName("请求 → 接受 → 出现在好友列表 → 单聊能发消息（这就是此前所有消息用例自己造数据的那条链）")
    void fullFriendRoundTrip() {
        Account bob = newAccount("it_fr_b1_" + SUFFIX);

        // ---------- 发起 ----------
        Map<String, Object> requested = ok(post("/v1/friends/requests",
                Map.of("target", "@" + bob.handle(), "message", "你好，我是 Alice"), alice.token()));
        assertSnakeCase(requested, "request_id", "from_actor", "to_actor", "status", "expires_at");
        long requestId = number(requested.get("request_id"));
        assertThat(number(requested.get("from_actor"))).isEqualTo(alice.actorId());
        assertThat(number(requested.get("status"))).isEqualTo(1L);

        // ---------- 对方能看到这条请求，且方向是 incoming ----------
        Map<String, Object> listed = ok(get("/v1/friends/requests?direction=incoming&status=pending",
                bob.token()));
        List<Object> items = list(listed.get("items"));
        assertThat(items).hasSize(1);
        Map<String, Object> item = map(items.get(0));
        assertSnakeCase(item, "request_id", "from_actor", "to_actor", "message", "status",
                "created_at", "expires_at");
        assertThat(number(item.get("request_id"))).isEqualTo(requestId);
        assertThat(map(item.get("from_actor")).get("handle")).isEqualTo(alice.handle());
        assertThat(item.get("message")).isEqualTo("你好，我是 Alice");

        // ---------- 同意 ----------
        Map<String, Object> accepted = ok(post("/v1/friends/requests/" + requestId + "/accept",
                Map.of(), bob.token()));
        assertSnakeCase(accepted, "request_id", "actor_a", "actor_b", "status", "updated_at", "conv_id");
        assertThat(number(accepted.get("status"))).isEqualTo(2L);
        long convId = number(accepted.get("conv_id"));
        conversations.add(convId);
        assertThat(convId).isPositive();

        // ---------- 好友列表（两个方向都看得到） ----------
        Map<String, Object> aliceFriends = ok(get("/v1/friends", alice.token()));
        Map<String, Object> row = map(list(aliceFriends.get("items")).get(0));
        assertSnakeCase(row, "actor_id", "actor_type", "handle", "display_name", "friends_since");
        assertThat(number(row.get("actor_id"))).isEqualTo(bob.actorId());
        assertThat(row.get("friends_since")).isNotNull();

        Map<String, Object> bobFriends = ok(get("/v1/friends", bob.token()));
        assertThat(number(map(list(bobFriends.get("items")).get(0)).get("actor_id")))
                .isEqualTo(alice.actorId());

        // ---------- 单聊能发了（这就是「非好友不能发消息」的正例） ----------
        ok(post("/v1/conversations/" + convId + "/messages",
                Map.of("msg_type", "TEXT", "client_msg_id", "it-fr-1",
                        "content", Map.of("text", "成为好友了")), alice.token()));

        // ---------- 幂等：再同意一次拿同一个会话 ----------
        Map<String, Object> again = ok(post("/v1/friends/requests/" + requestId + "/accept",
                Map.of(), bob.token()));
        assertThat(number(again.get("conv_id"))).isEqualTo(convId);
    }

    @Test
    @DisplayName("删好友之后单聊发不出去（40003），但历史消息仍然读得到（§3.5）")
    void removingFriendBlocksMessagesButKeepsHistory() {
        Account bob = newAccount("it_fr_b2_" + SUFFIX);
        long convId = becomeFriends(alice, bob);
        ok(post("/v1/conversations/" + convId + "/messages",
                Map.of("msg_type", "TEXT", "client_msg_id", "it-fr-2",
                        "content", Map.of("text", "删我之前")), alice.token()));

        Map<String, Object> removed = ok(remove("/v1/friends/" + bob.actorId(), alice.token()));
        assertSnakeCase(removed, "actor_id", "target_id", "removed");
        assertThat(removed.get("removed")).isEqualTo(true);

        // 发消息：40003（业务失败 + HTTP 200）
        ResponseEntity<Map<String, Object>> denied = post("/v1/conversations/" + convId + "/messages",
                Map.of("msg_type", "TEXT", "client_msg_id", "it-fr-3",
                        "content", Map.of("text", "还能发吗")), alice.token());
        assertThat(denied.getStatusCode().value()).isEqualTo(200);
        assertThat(number(body(denied).get("code"))).isEqualTo(40003L);

        // 历史仍可见
        Map<String, Object> history = ok(get("/v1/conversations/" + convId + "/messages", alice.token()));
        assertThat(list(history.get("items"))).hasSize(1);

        // 再删一次：幂等（removed=false），不是错误
        Map<String, Object> twice = ok(remove("/v1/friends/" + bob.actorId(), alice.token()));
        assertThat(twice.get("removed")).isEqualTo(false);
    }

    @Test
    @DisplayName("拉黑：对方发消息得到 40304（不是 40003），且不能再发好友请求（40903）")
    void blockStopsMessagesAndRequests() {
        Account bob = newAccount("it_fr_b3_" + SUFFIX);
        long convId = becomeFriends(alice, bob);

        ok(post("/v1/friends/" + bob.actorId() + "/block", Map.of(), alice.token()));

        ResponseEntity<Map<String, Object>> blocked = post("/v1/conversations/" + convId + "/messages",
                Map.of("msg_type", "TEXT", "client_msg_id", "it-fr-4",
                        "content", Map.of("text", "hi")), bob.token());
        assertThat(number(body(blocked).get("code")))
                .as("40304 的客户端动作是「停止重试」，与 40003 的「去加好友」完全不同")
                .isEqualTo(40304L);

        assertThat(number(body(post("/v1/friends/requests",
                Map.of("target", "@" + alice.handle()), bob.token())).get("code")))
                .isEqualTo(40903L);

        // 解除拉黑之后：关系被删掉了（§3.6 只删 BLOCKED 的行），所以又回到「不是好友」
        Map<String, Object> unblocked = ok(remove("/v1/friends/" + bob.actorId() + "/block",
                alice.token()));
        assertThat(unblocked.get("blocked")).isEqualTo(false);
        assertThat(number(body(post("/v1/conversations/" + convId + "/messages",
                Map.of("msg_type", "TEXT", "client_msg_id", "it-fr-5",
                        "content", Map.of("text", "hi")), bob.token())).get("code")))
                .as("解除拉黑不等于恢复好友：还要重新加一次")
                .isEqualTo(40003L);

        // 重新请求也不再被 40903 挡住
        assertThat(number(ok(post("/v1/friends/requests",
                Map.of("target", "@" + alice.handle()), bob.token())).get("status")))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("拒绝：整行删除（响应 status=0），此后双方都能重新发起")
    void rejectDeletesTheRow() {
        Account bob = newAccount("it_fr_b4_" + SUFFIX);
        long requestId = number(ok(post("/v1/friends/requests",
                Map.of("target", "@" + bob.handle()), alice.token())).get("request_id"));

        Map<String, Object> rejected = ok(post("/v1/friends/requests/" + requestId + "/reject",
                Map.of(), bob.token()));
        assertThat(number(rejected.get("status")))
                .as("0 不是状态码，含义是「这段关系已不存在」")
                .isZero();
        assertThat(rejected.get("conv_id")).as("拒绝不该建会话").isNull();
        assertThat(number(rejected.get("request_id"))).isEqualTo(requestId);

        assertThat(list(ok(get("/v1/friends/requests?direction=incoming", bob.token())).get("items")))
                .isEmpty();
        assertThat(number(ok(post("/v1/friends/requests",
                Map.of("target", "@" + bob.handle()), alice.token())).get("status")))
                .as("被拒绝过不留痕：重新发起是干净的 PENDING")
                .isEqualTo(1L);
    }

    // ================================================================ 错误码

    @Test
    @DisplayName("错误码：40904 加自己、40401 查不到人、40902 重复请求、40400 不存在的请求、40302 替别人同意")
    void errorCodesOverHttp() {
        Account bob = newAccount("it_fr_b5_" + SUFFIX);

        assertThat(number(body(post("/v1/friends/requests",
                Map.of("target", "@" + alice.handle()), alice.token())).get("code")))
                .isEqualTo(40904L);
        assertThat(number(body(post("/v1/friends/requests",
                Map.of("target", "@it_no_such_handle_at_all"), alice.token())).get("code")))
                .isEqualTo(40401L);

        long requestId = number(ok(post("/v1/friends/requests",
                Map.of("target_actor_id", bob.actorId()), alice.token())).get("request_id"));
        assertThat(number(body(post("/v1/friends/requests",
                Map.of("target", "@" + bob.handle()), alice.token())).get("code")))
                .isEqualTo(40902L);

        assertThat(number(body(post("/v1/friends/requests/999999999/accept",
                Map.of(), bob.token())).get("code")))
                .as("不存在的请求是 40400（通用资源不存在）")
                .isEqualTo(40400L);

        // 第三方替别人同意：40302，HTTP 403
        Account carol = newAccount("it_fr_b6_" + SUFFIX);
        ResponseEntity<Map<String, Object>> forbidden = post(
                "/v1/friends/requests/" + requestId + "/accept", Map.of(), carol.token());
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
        assertThat(number(body(forbidden).get("code"))).isEqualTo(40302L);

        // 发起人自己也不能同意
        assertThat(number(body(post("/v1/friends/requests/" + requestId + "/accept",
                Map.of(), alice.token())).get("code")))
                .isEqualTo(40302L);

        // 参数：既没给 target 也没给 target_actor_id → 40001；附言超长 → 40002
        assertThat(number(body(post("/v1/friends/requests", Map.of(), alice.token())).get("code")))
                .isEqualTo(40001L);
        assertThat(number(body(post("/v1/friends/requests",
                Map.of("target", "@" + bob.handle(), "message", "x".repeat(256)), alice.token())
        ).get("code"))).isEqualTo(40002L);

        // direction 写错 → 40002（而不是静默按默认值处理）
        assertThat(number(body(get("/v1/friends/requests?direction=sideways", alice.token()))
                .get("code"))).isEqualTo(40002L);
    }

    // ================================================================ 分页（只有真实 SQL 才验得了）

    @Test
    @DisplayName("好友列表分页：3 个好友按 limit=2 翻两页，不重不漏")
    void friendListPaginatesWithoutGaps() {
        // 专门用一个新账号，而不是 alice：本类的用例共用 alice，
        // 而「恰好翻出几个人」这类断言只有在候选集确定时才有意义
        // （否则它会随测试执行顺序而变化——那是测试自己的耦合，不是被测代码的缺陷）。
        Account pager = newAccount("it_fr_pager_" + SUFFIX);
        Account bob = newAccount("it_fr_p1_" + SUFFIX);
        Account carol = newAccount("it_fr_p2_" + SUFFIX);
        Account dave = newAccount("it_fr_p3_" + SUFFIX);
        becomeFriends(pager, bob);
        becomeFriends(pager, carol);
        becomeFriends(pager, dave);

        Map<String, Object> first = ok(get("/v1/friends?limit=2", pager.token()));
        assertSnakeCase(first, "items", "next_cursor", "has_more");
        assertThat(list(first.get("items"))).hasSize(2);
        assertThat(first.get("has_more")).isEqualTo(true);
        String cursor = (String) first.get("next_cursor");
        assertThat(cursor).isNotNull();

        Map<String, Object> second = ok(get("/v1/friends?limit=2&cursor=" + cursor, pager.token()));
        List<Object> secondItems = list(second.get("items"));
        assertThat(secondItems).as("第三个人在第二页").hasSize(1);
        assertThat(second.get("has_more")).isEqualTo(false);
        assertThat(second.get("next_cursor")).as("没有更多时游标必须是 null").isNull();

        // 两页合起来正好是三个人，且没有重复
        Set<Long> ids = new java.util.HashSet<>();
        for (Object row : list(first.get("items"))) {
            ids.add(number(map(row).get("actor_id")));
        }
        for (Object row : secondItems) {
            ids.add(number(map(row).get("actor_id")));
        }
        assertThat(ids).containsExactlyInAnyOrder(bob.actorId(), carol.actorId(), dave.actorId());
    }

    @Test
    @DisplayName("请求列表：过期的待处理请求不出现在 pending 视图里，但 status=all 看得到")
    void expiredRequestIsHiddenFromPendingView() {
        Account bob = newAccount("it_fr_b7_" + SUFFIX);
        long requestId = number(ok(post("/v1/friends/requests",
                Map.of("target", "@" + bob.handle()), alice.token())).get("request_id"));

        // 直接把这一行改成「两周前就已经过期」（模拟放了两周的请求）
        jdbc.update("UPDATE friendship SET created_at = ?, expires_at = ?, updated_at = ?"
                        + " WHERE request_id = ?",
                LocalDateTime.now().minusDays(14), LocalDateTime.now().minusDays(7),
                LocalDateTime.now().minusDays(14), requestId);

        // 断言的是「这一个 request_id 在不在」，而不是「列表是不是空的」：
        // alice 是本类共用的账号，其他用例也会给她留下待处理请求。
        assertThat(requestIds(ok(get("/v1/friends/requests?direction=outgoing&status=pending",
                alice.token()))))
                .as("过期的请求按「不存在」处理")
                .doesNotContain(requestId);

        assertThat(requestIds(ok(get("/v1/friends/requests?direction=outgoing&status=all",
                alice.token()))))
                .as("status=all 不过滤过期（客户端要看得到历史）")
                .contains(requestId);

        // 过期之后可以重新发起：换一个新的 request_id
        long renewed = number(ok(post("/v1/friends/requests",
                Map.of("target", "@" + bob.handle()), alice.token())).get("request_id"));
        assertThat(renewed).isNotEqualTo(requestId);

        // 而旧的 request_id 已经不在表里（被新请求覆盖）
        assertThat(number(body(post("/v1/friends/requests/" + requestId + "/accept",
                Map.of(), bob.token())).get("code")))
                .isEqualTo(40400L);
    }

    @Test
    @DisplayName("游标贴错类型会被拒（40010），而不是返回一个内容不对的列表")
    void wrongCursorTypeRejected() {
        // 会话列表的游标贴到好友列表上（两个游标的形状一样，只有 t 不同）
        String foreign = "eyJ2IjoxLCJ0IjoiY29udiIsImF0IjoxNzY3MjI1NjAwMDAwLCJjaWQiOjF9";
        assertThat(number(body(get("/v1/friends?cursor=" + foreign, alice.token())).get("code")))
                .isEqualTo(40010L);
        assertThat(number(body(get("/v1/friends/requests?cursor=" + foreign, alice.token())).get("code")))
                .as("好友列表与请求列表的游标是两种类型，不能互相贴")
                .isEqualTo(40010L);
    }

    // ================================================================ 工具

    /** 列表里的 request_id 集合（断言「某一条在不在」，而不是「列表是不是空的」）。 */
    private static Set<Long> requestIds(Map<String, Object> page) {
        Set<Long> ids = new java.util.HashSet<>();
        for (Object row : list(page.get("items"))) {
            ids.add(number(map(row).get("request_id")));
        }
        return ids;
    }

    /** 走真实路径成为好友，返回单聊会话 id。 */
    private long becomeFriends(Account one, Account other) {
        long requestId = number(ok(post("/v1/friends/requests",
                Map.of("target", "@" + other.handle()), one.token())).get("request_id"));
        long convId = number(ok(post("/v1/friends/requests/" + requestId + "/accept",
                Map.of(), other.token())).get("conv_id"));
        conversations.add(convId);
        return convId;
    }

    private Account newAccount(String handle) {
        handles.add(handle);
        Map<String, Object> data = ok(post("/v1/auth/register",
                Map.of("handle", handle, "password", PASSWORD, "display_name", handle), null));
        refreshTokens.add((String) data.get("refresh_token"));
        return new Account(number(data.get("actor_id")), handle, (String) data.get("access_token"));
    }

    private ResponseEntity<Map<String, Object>> get(String path, String bearer) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers(bearer)), JSON_MAP);
    }

    private ResponseEntity<Map<String, Object>> post(String path, Object body, String bearer) {
        HttpHeaders headers = headers(bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url(path), HttpMethod.POST,
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
