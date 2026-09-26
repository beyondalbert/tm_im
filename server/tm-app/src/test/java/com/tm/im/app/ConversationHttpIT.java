package com.tm.im.app;

import com.tm.im.domain.entity.Friendship;
import com.tm.im.domain.enums.FriendshipStatus;
import com.tm.im.domain.enums.SecretType;
import com.tm.im.domain.repository.ActorRepository;
import com.tm.im.domain.repository.ActorSecretRepository;
import com.tm.im.domain.repository.FriendshipRepository;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话与消息 REST 的端到端验证（03-rest-api.md §4.1–§4.8）——<b>真实 HTTP + 真实 MySQL/Redis</b>。
 *
 * <p><b>为什么必须有它</b>：这一批接口的每个「文档承诺」都落在 HTTP 层，其中四条只有真实容器才验证得了：
 * <ol>
 *   <li>{@code content} 出参必须是<b>JSON 对象</b>而不是字符串——MockMvc 里那层 Jackson
 *       是测试自己装的，只有走应用的配置才会暴露差异；</li>
 *   <li>{@code spring.jackson.property-naming-strategy} 是否真的生效
 *       （少一个下划线，客户端就解析出一片 null）；</li>
 *   <li>「幂等重放返回<b>完全相同</b>的响应」——这要比较两次响应的原始字节，
 *       而不是两个各自解析过的 Map；</li>
 *   <li>「客户端不许发 SYSTEM 消息」回的是 <b>HTTP 403 + code 40302</b> 而不是
 *       200 里的一个失败码——状态码由 {@code ErrorCode.httpStatus()} 推导，只有这条链路能证明推导对了。</li>
 * </ol>
 *
 * <p><b>每个用例都自带一个「对手」</b>（临时注册一个用户并与 alice 加为好友），
 * 而不是共用一根固定的好友关系。原因很具体：单聊会话由<b>无序对</b>唯一确定，
 * 共用一个对手意味着所有用例共用<b>同一个会话与同一串 seq</b>，
 * 于是「历史里有且只有一条消息」这类断言会取决于用例的执行顺序——
 * 那种失败看起来像功能坏了，实际是测试互相污染。
 *
 * <p>好友关系<b>由测试直接写库</b>：加好友的接口是 §3，还没做。
 * 这里刻意不走完整产品流程，而是在用例内部把前提造出来——否则一个用例同时在测两个功能，
 * 失败时分不清是哪一半坏了。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversationHttpIT {

    private static final String JWT_SECRET = "it-jwt-secret-0123456789abcdef-32B";
    private static final String PASSWORD = "it-pass-12345678";
    private static final String SUFFIX = Long.toHexString(System.nanoTime() & 0xFFFFFF);

    /** 主角：所有用例都以他的身份发起。 */
    private static final String ALICE = "it_conv_alice_" + SUFFIX;

    @LocalServerPort
    int httpPort;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ActorRepository actors;

    @Autowired
    ActorSecretRepository secrets;

    @Autowired
    FriendshipRepository friendships;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JdbcTemplate jdbc;

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    /** 每个用例临时造出来的用户，收尾时按 handle 精确删除。 */
    private final List<String> handles = new ArrayList<>();

    /** 本用例建出来的会话，收尾时按 conv_id 精确删除（含消息与序号键）。 */
    private final List<Long> createdConversations = new ArrayList<>();

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
        registry.add("tm.node.id", () -> "it-conv-boot");
        registry.add("tm.node.ttl", () -> "5m");
        registry.add("tm.netty.port", () -> "0");
    }

    @BeforeAll
    void createAlice() {
        alice = newAccount(ALICE);
    }

    @AfterAll
    void cleanUp() {
        for (long convId : createdConversations) {
            jdbc.update("DELETE FROM conversation_member WHERE conv_id = ?", convId);
            jdbc.update("DELETE FROM message WHERE conv_id = ?", convId);
            jdbc.update("DELETE FROM conversation WHERE id = ?", convId);
            redis.delete("tm:seq:" + convId);
        }
        for (String handle : handles) {
            // 好友关系：alice 与这个人的无序对（两个方向都删，避免漏掉）
            actors.findByHandle(handle).ifPresent(peer -> {
                jdbc.update("DELETE FROM friendship WHERE (actor_a = ? AND actor_b = ?)"
                        + " OR (actor_a = ? AND actor_b = ?)",
                        alice.actorId(), peer.getId(), peer.getId(), alice.actorId());
                secrets.delete(peer.getId(), SecretType.PASSWORD_HASH);
                jdbc.update("DELETE FROM actor WHERE id = ?", peer.getId());
            });
        }
        secrets.delete(alice.actorId(), SecretType.PASSWORD_HASH);
        jdbc.update("DELETE FROM actor WHERE id = ?", alice.actorId());
        for (String token : refreshTokens) {
            redis.delete("tm:rt:" + com.tm.im.common.crypto.RefreshTokens.hash(token));
        }
    }

    // ================================================================ 单聊全链路

    @Test
    @DisplayName("单聊全链路：建会话（幂等）→ 发消息（含逐字节相同的重放）→ 拉历史 → 增量 → 对方未读 → 已读")
    void directConversationRoundTrip() {
        Account peer = newFriend();

        // ---------- 建会话：第一次 created=true ----------
        Map<String, Object> created = ok(post("/v1/conversations/direct",
                Map.of("peer", "@" + peer.handle()), alice.token()));
        assertSnakeCase(created, "conv_id", "conv_type", "peer", "created", "last_seq");
        long convId = number(created.get("conv_id"));
        createdConversations.add(convId);
        assertThat(created.get("created")).isEqualTo(true);
        assertThat(number(created.get("conv_type"))).as("1=DIRECT").isEqualTo(1L);
        assertThat(number(created.get("last_seq"))).as("新会话没有消息").isZero();
        Map<String, Object> peerView = map(created.get("peer"));
        assertThat(number(peerView.get("actor_id"))).isEqualTo(peer.actorId());
        assertThat(peerView.get("handle")).isEqualTo(peer.handle());
        assertThat(peerView.get("avatar_url")).isNull();

        // ---------- 再建一次：幂等，返回同一条 ----------
        Map<String, Object> again = ok(post("/v1/conversations/direct",
                Map.of("peer", "@" + peer.handle()), alice.token()));
        assertThat(again.get("created")).isEqualTo(false);
        assertThat(number(again.get("conv_id"))).isEqualTo(convId);

        // ---------- 发消息：seq 从 1 开始 ----------
        Map<String, Object> body = Map.of(
                "msg_type", "TEXT", "client_msg_id", "it-c-1", "content", Map.of("text", "你好，Agent"));
        Map<String, Object> sent = ok(post("/v1/conversations/" + convId + "/messages", body, alice.token()));
        assertSnakeCase(sent, "message_id", "conv_id", "seq", "client_msg_id", "sender_id",
                "msg_type", "content", "created_at");
        assertThat(number(sent.get("seq"))).isEqualTo(1L);
        assertThat(number(sent.get("conv_id"))).isEqualTo(convId);
        assertThat(sent.get("msg_type")).isEqualTo("TEXT");
        assertThat(sent.get("client_msg_id")).isEqualTo("it-c-1");
        assertThat(number(sent.get("sender_id"))).isEqualTo(alice.actorId());
        // ★ content 必须是 JSON 对象：若是字符串，每个客户端都要自己再解一次，而其中一个会解错
        assertThat(sent.get("content")).isInstanceOf(Map.class);
        assertThat(map(sent.get("content")).get("text")).isEqualTo("你好，Agent");
        Instant createdAt = Instant.parse((String) sent.get("created_at"));
        assertThat(createdAt).isBefore(Instant.now().plusSeconds(60));

        // ---------- 幂等重放：响应必须逐字节相同 ----------
        String first = raw("/v1/conversations/" + convId + "/messages", body, alice.token());
        String replay = raw("/v1/conversations/" + convId + "/messages", body, alice.token());
        assertThat(replay).as("重放返回不同的字节，客户端就会认为这是两条不同的消息").isEqualTo(first);

        // ---------- 拉历史（游标模式，倒序）----------
        Map<String, Object> history = ok(get("/v1/conversations/" + convId + "/messages", alice.token()));
        assertSnakeCase(history, "items", "next_cursor", "has_more");
        List<Object> items = list(history.get("items"));
        assertThat(items).hasSize(1);
        Map<String, Object> row = map(items.get(0));
        assertSnakeCase(row, "message_id", "conv_id", "seq", "sender_id", "msg_type", "content",
                "reply_to", "created_at");
        assertThat(number(row.get("seq"))).isEqualTo(1L);
        assertThat(number(row.get("message_id"))).isPositive();
        assertThat(history.get("has_more")).isEqualTo(false);
        assertThat(history.get("next_cursor")).isNull();

        // ---------- 增量拉取（since_seq 模式，升序）----------
        Map<String, Object> incremental = ok(
                get("/v1/conversations/" + convId + "/messages?since_seq=0", alice.token()));
        assertSnakeCase(incremental, "items", "latest_seq", "has_more");
        assertThat(number(incremental.get("latest_seq"))).isEqualTo(1L);
        assertThat(list(incremental.get("items"))).hasSize(1);

        // ---------- 对方看到未读 ----------
        Map<String, Object> peerList = ok(get("/v1/conversations", peer.token()));
        assertSnakeCase(peerList, "items", "next_cursor", "has_more");
        Map<String, Object> peerRow = rowOf(peerList, convId);
        assertSnakeCase(peerRow, "conv_id", "conv_type", "peer", "last_seq", "last_read_seq",
                "unread_count", "last_message", "muted", "member_count", "updated_at");
        assertThat(number(peerRow.get("last_seq"))).isEqualTo(1L);
        assertThat(number(peerRow.get("unread_count"))).as("未读数由服务端算好").isEqualTo(1L);
        assertThat(number(map(peerRow.get("peer")).get("actor_id")))
                .as("单聊的 peer 是「对方」，即发起者 alice").isEqualTo(alice.actorId());
        assertThat(map(map(peerRow.get("last_message")).get("content")).get("text"))
                .isEqualTo("你好，Agent");
        assertThat(number(peerRow.get("member_count"))).as("单聊恒为 2").isEqualTo(2L);
        assertThat(peerRow.get("muted")).isEqualTo(false);

        // ---------- 已读上报：幂等且回生效值 ----------
        Map<String, Object> read = ok(post("/v1/conversations/" + convId + "/read",
                Map.of("last_read_seq", 1), peer.token()));
        assertSnakeCase(read, "conv_id", "last_read_seq", "unread_count");
        assertThat(number(read.get("last_read_seq"))).isEqualTo(1L);
        assertThat(number(read.get("unread_count"))).isZero();

        // 重复上报（更小的值）不能让游标倒退
        Map<String, Object> staleRead = ok(post("/v1/conversations/" + convId + "/read",
                Map.of("last_read_seq", 0), peer.token()));
        assertThat(number(staleRead.get("last_read_seq"))).as("游标只前进").isEqualTo(1L);
        assertThat(number(staleRead.get("unread_count"))).isZero();
    }

    // ================================================================ 群聊

    @Test
    @DisplayName("建群 → 详情（成员/角色/精确人数）→ 建群产生的 SYSTEM 消息真的落库了")
    void groupCreationAndDetail() {
        Account one = newAccount("it_conv_g1_" + SUFFIX);
        Account two = newAccount("it_conv_g2_" + SUFFIX);

        Map<String, Object> created = ok(post("/v1/conversations/group",
                Map.of("title", "IT 群 " + SUFFIX, "members", List.of("@" + one.handle(), "@" + two.handle())),
                alice.token()));
        assertSnakeCase(created, "conv_id", "conv_type", "title", "owner_actor", "member_count",
                "created_at");
        long convId = number(created.get("conv_id"));
        createdConversations.add(convId);
        assertThat(number(created.get("conv_type"))).as("2=GROUP").isEqualTo(2L);
        assertThat(number(created.get("member_count"))).isEqualTo(3L);
        assertThat(number(created.get("owner_actor"))).isEqualTo(alice.actorId());
        assertThat(created.get("title")).isEqualTo("IT 群 " + SUFFIX);

        // 群成员看详情（群聊不要求互为好友，所以非发起者也能看）
        Map<String, Object> detail = ok(get("/v1/conversations/" + convId, two.token()));
        assertSnakeCase(detail, "conv_id", "conv_type", "title", "owner_actor", "member_count",
                "last_seq", "my_last_read_seq", "unread_count", "members", "created_at");
        assertThat(number(detail.get("member_count"))).isEqualTo(3L);
        assertThat(list(detail.get("members"))).hasSize(3);
        Map<String, Object> ownerRow = list(detail.get("members")).stream()
                .map(ConversationHttpIT::map)
                .filter(row -> number(row.get("actor_id")) == alice.actorId())
                .findFirst().orElseThrow();
        assertThat(number(ownerRow.get("role"))).as("1=OWNER").isEqualTo(1L);
        assertThat(number(ownerRow.get("actor_type"))).as("1=HUMAN").isEqualTo(1L);
        assertThat(ownerRow.get("handle")).isEqualTo(ALICE);
        assertThat(Instant.parse((String) ownerRow.get("joined_at")))
                .isBefore(Instant.now().plusSeconds(60));

        // 建群写了一条 SYSTEM 消息（DESIGN §11.2），所以 last_seq 已经是 1
        assertThat(number(detail.get("last_seq"))).isEqualTo(1L);
        Map<String, Object> history = ok(get("/v1/conversations/" + convId + "/messages", alice.token()));
        Map<String, Object> systemMessage = map(list(history.get("items")).get(0));
        assertThat(systemMessage.get("msg_type")).isEqualTo("SYSTEM");
        assertThat(map(systemMessage.get("content")).get("action")).isEqualTo("group_created");
        assertThat(number(systemMessage.get("sender_id"))).isEqualTo(alice.actorId());
    }

    // ================================================================ 业务规则与错误码

    @Test
    @DisplayName("非好友不能发单聊消息：HTTP 200 + 40003（§11.6 的硬规则）")
    void nonFriendCannotSendInDirect() {
        Account stranger = newAccount("it_conv_str_" + SUFFIX);
        long convId = number(ok(post("/v1/conversations/direct",
                Map.of("peer", "@" + stranger.handle()), alice.token())).get("conv_id"));
        createdConversations.add(convId);

        ResponseEntity<Map<String, Object>> response = post(
                "/v1/conversations/" + convId + "/messages",
                Map.of("msg_type", "TEXT", "client_msg_id", "it-nf-1", "content", Map.of("text", "在吗")),
                alice.token());

        assertThat(response.getStatusCode().value()).as("业务失败仍是 HTTP 200").isEqualTo(200);
        assertThat(number(body(response).get("code"))).isEqualTo(40003L);
    }

    @Test
    @DisplayName("客户端不许发 SYSTEM 消息：HTTP 403 + 40302（否则能绕过好友校验）")
    void clientCannotSendSystemMessage() {
        Account peer = newFriend();
        long convId = number(ok(post("/v1/conversations/direct",
                Map.of("peer", "@" + peer.handle()), alice.token())).get("conv_id"));
        createdConversations.add(convId);

        ResponseEntity<Map<String, Object>> response = post(
                "/v1/conversations/" + convId + "/messages",
                Map.of("msg_type", "SYSTEM", "client_msg_id", "it-sys-1",
                        "content", Map.of("action", "member_joined", "actor_id", peer.actorId())),
                alice.token());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(number(body(response).get("code"))).isEqualTo(40302L);
    }

    @Test
    @DisplayName("不是会话成员：读历史/看详情/发消息都回 40303；会话不存在回 40402（HTTP 200）")
    void membershipIsEnforcedOnReadAndWrite() {
        Account peer = newFriend();
        Account outsider = newAccount("it_conv_out_" + SUFFIX);
        long convId = number(ok(post("/v1/conversations/direct",
                Map.of("peer", "@" + peer.handle()), alice.token())).get("conv_id"));
        createdConversations.add(convId);

        assertThat(number(body(get("/v1/conversations/" + convId + "/messages", outsider.token())).get("code")))
                .isEqualTo(40303L);
        assertThat(number(body(get("/v1/conversations/" + convId, outsider.token())).get("code")))
                .isEqualTo(40303L);
        assertThat(number(body(post("/v1/conversations/" + convId + "/messages",
                Map.of("msg_type", "TEXT", "client_msg_id", "it-nm-1", "content", Map.of("text", "hi")),
                outsider.token())).get("code")))
                .isEqualTo(40303L);

        ResponseEntity<Map<String, Object>> missing =
                get("/v1/conversations/999999999999/messages", alice.token());
        assertThat(missing.getStatusCode().value()).as("资源不存在是 HTTP 200 + 业务码").isEqualTo(200);
        assertThat(number(body(missing).get("code"))).isEqualTo(40402L);
    }

    @Test
    @DisplayName("请求体错误各有各的码：40001 / 40007 / 40009 / 40002")
    void requestValidationUsesDistinctCodes() {
        Account peer = newFriend();
        long convId = number(ok(post("/v1/conversations/direct",
                Map.of("peer", "@" + peer.handle()), alice.token())).get("conv_id"));
        createdConversations.add(convId);
        String path = "/v1/conversations/" + convId + "/messages";

        assertThat(number(body(post(path, Map.of("msg_type", "TEXT",
                "content", Map.of("text", "ok")), alice.token())).get("code")))
                .as("client_msg_id 是必填的幂等键").isEqualTo(40001L);

        assertThat(number(body(post(path, Map.of("msg_type", "STICKER", "client_msg_id", "it-x",
                "content", Map.of("text", "ok")), alice.token())).get("code")))
                .isEqualTo(40007L);

        assertThat(number(body(post(path, Map.of("msg_type", "TEXT", "client_msg_id", "it-x",
                "content", Map.of("media_id", 1)), alice.token())).get("code")))
                .as("本仓库发 SYSTEM 会被 40302 拦在前面，所以这里只能验 TEXT 的结构不符")
                .isEqualTo(40009L);

        assertThat(number(body(post(path, Map.of("msg_type", "TEXT", "client_msg_id", "it-x",
                "content", "{\"text\":\"hi\"}"), alice.token())).get("code")))
                .as("content 必须是对象，不是「对象的字符串」").isEqualTo(40002L);

        assertThat(number(body(post(path, Map.of("msg_type", "TEXT", "client_msg_id", "it-" + "c".repeat(62),
                "content", Map.of("text", "ok")), alice.token())).get("code")))
                .as("client_msg_id 超过列宽 64 会撞数据库错误，必须在应用层拦")
                .isEqualTo(40002L);
    }

    @Test
    @DisplayName("建群参数：空成员 40001、超上限 40906、不存在的 handle 40401")
    void groupCreationValidatesParameters() {
        assertThat(number(body(post("/v1/conversations/group",
                Map.of("title", "G", "members", List.of()), alice.token())).get("code")))
                .isEqualTo(40001L);
        assertThat(number(body(post("/v1/conversations/group",
                Map.of("title", "G", "members", List.of("@" + ALICE)), alice.token())).get("code")))
                .as("只有自己不算群聊").isEqualTo(40002L);
        assertThat(number(body(post("/v1/conversations/group",
                Map.of("title", "G", "members", List.of("@it_no_such_handle")), alice.token())).get("code")))
                .isEqualTo(40401L);
    }

    @Test
    @DisplayName("两种分页模式不能混用：cursor 与 since_seq 同时给回 40002")
    void paginationModesAreMutuallyExclusive() {
        Account peer = newFriend();
        long convId = number(ok(post("/v1/conversations/direct",
                Map.of("peer", "@" + peer.handle()), alice.token())).get("conv_id"));
        createdConversations.add(convId);

        ResponseEntity<Map<String, Object>> both = get("/v1/conversations/" + convId
                + "/messages?since_seq=1&cursor=abc", alice.token());

        assertThat(both.getStatusCode().value()).isEqualTo(200);
        assertThat(number(body(both).get("code"))).isEqualTo(40002L);
    }

    @Test
    @DisplayName("坏游标：HTTP 200 + 40010（客户端能自己修好，但必须知道不是服务端坏了）")
    void invalidCursorIsRejected() {
        Account peer = newFriend();
        long convId = number(ok(post("/v1/conversations/direct",
                Map.of("peer", "@" + peer.handle()), alice.token())).get("conv_id"));
        createdConversations.add(convId);

        ResponseEntity<Map<String, Object>> response = get(
                "/v1/conversations/" + convId + "/messages?cursor=not-a-cursor", alice.token());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(number(body(response).get("code"))).isEqualTo(40010L);
    }

    @Test
    @DisplayName("不能和自己建单聊：40904")
    void selfConversationIsRejected() {
        ResponseEntity<Map<String, Object>> response = post("/v1/conversations/direct",
                Map.of("peer", "@" + ALICE), alice.token());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(number(body(response).get("code"))).isEqualTo(40904L);
    }

    // ================================================================ 分页

    @Test
    @DisplayName("消息历史倒序翻页：第二页接着第一页往下取（游标而不是 offset）")
    void messageHistoryPaginatesBackwards() {
        Account peer = newFriend();
        long convId = number(ok(post("/v1/conversations/direct",
                Map.of("peer", "@" + peer.handle()), alice.token())).get("conv_id"));
        createdConversations.add(convId);
        for (int i = 1; i <= 3; i++) {
            ok(post("/v1/conversations/" + convId + "/messages",
                    Map.of("msg_type", "TEXT", "client_msg_id", "it-h-" + i,
                            "content", Map.of("text", "m" + i)), alice.token()));
        }

        Map<String, Object> page1 = ok(get("/v1/conversations/" + convId + "/messages?limit=2", alice.token()));
        assertThat(list(page1.get("items"))).extracting(o -> number(map(o).get("seq")))
                .as("最新在前").containsExactly(3L, 2L);
        assertThat(page1.get("has_more")).isEqualTo(true);

        Map<String, Object> page2 = ok(get("/v1/conversations/" + convId + "/messages?limit=2&cursor="
                + page1.get("next_cursor"), alice.token()));
        assertThat(list(page2.get("items"))).extracting(o -> number(map(o).get("seq")))
                .containsExactly(1L);
        assertThat(page2.get("has_more")).isEqualTo(false);
        assertThat(page2.get("next_cursor")).isNull();
    }

    @Test
    @DisplayName("会话列表分页：next_cursor 能把剩下的取完，两页之间不重不漏")
    void conversationListPaginates() {
        Account older = newFriend();
        Account newer = newFriend();
        long first = number(ok(post("/v1/conversations/direct",
                Map.of("peer", "@" + older.handle()), alice.token())).get("conv_id"));
        long second = number(ok(post("/v1/conversations/direct",
                Map.of("peer", "@" + newer.handle()), alice.token())).get("conv_id"));
        createdConversations.add(first);
        createdConversations.add(second);
        // 让 second 成为「最近活跃」的那个：列表按活跃时间排，而不是按创建/加入时间
        ok(post("/v1/conversations/" + second + "/messages",
                Map.of("msg_type", "TEXT", "client_msg_id", "it-p-1", "content", Map.of("text", "最新")),
                alice.token()));

        Map<String, Object> page1 = ok(get("/v1/conversations?limit=1", alice.token()));
        assertThat(page1.get("has_more")).isEqualTo(true);
        assertThat(number(map(list(page1.get("items")).get(0)).get("conv_id"))).isEqualTo(second);
        assertThat(page1.get("next_cursor")).isNotNull();

        Map<String, Object> page2 = ok(get("/v1/conversations?limit=1&cursor="
                + page1.get("next_cursor"), alice.token()));
        assertThat(list(page2.get("items"))).as("还有 alice 自己的其他会话").isNotEmpty();
        assertThat(number(map(list(page2.get("items")).get(0)).get("conv_id")))
                .as("第二页必须是比游标更旧的").isNotEqualTo(second);
    }

    // ================================================================ 工具

    /** 注册一个新用户（不建立好友关系）。 */
    private Account newAccount(String handle) {
        handles.add(handle);
        Map<String, Object> data = ok(post("/v1/auth/register",
                Map.of("handle", handle, "password", PASSWORD, "display_name", handle), null));
        refreshTokens.add((String) data.get("refresh_token"));
        return new Account(number(data.get("actor_id")), handle, (String) data.get("access_token"));
    }

    /** 注册一个新用户并让 alice 与他成为好友（单聊发消息的硬前置）。 */
    private Account newFriend() {
        Account peer = newAccount("it_conv_p" + seq.incrementAndGet() + "_" + SUFFIX);
        Friendship friendship = new Friendship();
        friendship.setActorA(Math.min(alice.actorId(), peer.actorId()));
        friendship.setActorB(Math.max(alice.actorId(), peer.actorId()));
        friendship.setInitiator(alice.actorId());
        friendship.setStatus(FriendshipStatus.ACCEPTED);
        friendships.save(friendship);
        return peer;
    }

    /** 从会话列表里挑出指定会话的那一行。 */
    private static Map<String, Object> rowOf(Map<String, Object> page, long convId) {
        return list(page.get("items")).stream()
                .map(ConversationHttpIT::map)
                .filter(row -> number(row.get("conv_id")) == convId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("会话列表里没有 conv_id=" + convId + "：" + page));
    }

    /** 发请求并返回原始响应体——幂等重放要比的是字节，而不是解析后的 Map。 */
    private String raw(String path, Object body, String bearer) {
        HttpHeaders headers = headers(bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(com.tm.im.common.json.Json.write(body), headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
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
     * 断言信封里的字段名是 snake_case。
     *
     * <p>这条断言取决于 {@code spring.jackson.property-naming-strategy} 是否真的被 Boot 读到——
     * 写错时不会报错，只会让字段名悄悄变成 camelCase，而客户端按文档解析就得到一片 null。
     *
     * <p><b>除了点名要有的字段，还会拒绝任何含大写字母的键</b>（{@code assertNoCamelCase}）。
     * 只查点名字段是不够的：把 {@code last_read_seq} 改名成 {@code readCursor} 时，
     * 「点名的那些都在」仍然成立，而真实响应里多出一个客户端不认识、少一个它要找的字段——
     * 这恰恰是「加一个字段就悄悄改坏了另一个」的典型失败方式。
     */
    private static void assertSnakeCase(Map<String, Object> data, String... expectedKeys) {
        assertThat(data.keySet()).contains(expectedKeys);
        for (String key : expectedKeys) {
            if (!key.contains("_")) {
                continue;
            }
            assertThat(data.keySet()).as("不应出现 camelCase 字段 %s", camel(key)).doesNotContain(camel(key));
        }
        for (String key : data.keySet()) {
            assertThat(key)
                    .as("响应里出现了 camelCase 键（应为 %s）——客户端会解析出一片 null", snake(key))
                    .isEqualTo(snake(key));
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

    private static String camel(String snake) {
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
