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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 群成员管理的端到端验证（03-rest-api.md §4.9）——<b>真实 HTTP + 真实 MySQL/Redis</b>。
 *
 * <p><b>为什么必须要它</b>，四条只有真实容器才验证得了：
 * <ol>
 *   <li><b>HTTP PATCH 到底通不通</b>：这两个路由的方法名是契约的一部分（§4.9 写的是 {@code PATCH}），
 *       而 Spring 的 PATCH 映射要经过容器的请求解析，只有真的发一次才知道；</li>
 *   <li>响应的字段名是不是 snake_case（{@code already_members} / {@code owner_actor}）——
 *       少一个下划线，客户端解析出一片 null；</li>
 *   <li>错误码对应的 <b>HTTP 状态码</b>：40305/40306 由 {@code ErrorCode.httpStatus()} 推导成
 *       <b>403</b>，而 40908/40904 是 200——两套都在这里，光看单测证明不了推导对了；</li>
 *   <li>成员管理产生的 SYSTEM 消息<b>真的落库了</b>（seq 分配、内容结构、扇出前的那一步）。</li>
 * </ol>
 *
 * <p><b>为什么单开一个类而不是接着 {@code ConversationHttpIT} 写</b>：那个类的 HTTP 客户端是
 * 默认的 {@code TestRestTemplate}（底层 {@code HttpURLConnection}），而它<b>不支持 PATCH</b>
 * （{@code ProtocolException: Invalid HTTP method: PATCH}）。这里要把请求工厂换成 JDK 的
 * {@code HttpClient}（见 {@link #usePatchCapableHttpClient()}），换掉之后整个类的每一次请求
 * 都走另一套实现——原先那 12 个用例的“绿”是在旧工厂上取得的证据，不该被这次替换重新解释一遍。
 *
 * <p><b>{@code tm.conversation.max-group-members} 被调成 4</b>（默认 500）：上限这类规则
 * 只有「刚好越界」那一次才看得见，为了它去造 500 个账号是不划算的。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversationMemberHttpIT {

    private static final String JWT_SECRET = "it-jwt-secret-0123456789abcdef-32B";
    private static final String PASSWORD = "it-pass-12345678";
    private static final String SUFFIX = Long.toHexString(System.nanoTime() & 0xFFFFFF);

    /** 单群上限，刻意调小（见类注释）。 */
    private static final int MAX_MEMBERS = 4;

    private static final String ALICE = "it_mem_alice_" + SUFFIX;

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
        registry.add("tm.node.id", () -> "it-member-boot");
        registry.add("tm.node.ttl", () -> "5m");
        registry.add("tm.netty.port", () -> "0");
        registry.add("tm.conversation.max-group-members", () -> String.valueOf(MAX_MEMBERS));
    }

    @BeforeAll
    void setUp() {
        usePatchCapableHttpClient();
        alice = newAccount(ALICE);
    }

    /**
     * 把 HTTP 客户端换成支持 PATCH 的实现。
     *
     * <p>{@code TestRestTemplate} 在 classpath 上没有 Apache HttpClient 时用
     * {@code SimpleClientHttpRequestFactory}，而它的底层是 {@code HttpURLConnection}——
     * 那个类的 {@code setRequestMethod} 只认 HTTP/1.1 规范里那几个方法，
     * 传 {@code PATCH} 会直接抛 {@code ProtocolException: Invalid HTTP method: PATCH}。
     * 客户端还没发出去就失败了，而报错里没有任何「路由/服务端」的字样，很容易被误读成服务端问题。
     *
     * <p><b>这不是服务端的问题</b>：Tomcat 与 Spring MVC 都支持 PATCH（curl / 浏览器 / axios 都发得出去），
     * 坏的只是这个测试工具链。JDK 自带的 {@code java.net.http.HttpClient} 支持任意方法，
     * 所以换请求工厂而不是改契约（把 PATCH 改成 POST 就是让实现去迁就测试工具了）。
     */
    private void usePatchCapableHttpClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
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
            actors.findByHandle(handle).ifPresent(peer -> {
                secrets.delete(peer.getId(), SecretType.PASSWORD_HASH);
                jdbc.update("DELETE FROM actor WHERE id = ?", peer.getId());
            });
        }
        for (String token : refreshTokens) {
            redis.delete("tm:rt:" + com.tm.im.common.crypto.RefreshTokens.hash(token));
        }
    }

    // ================================================================ 加人 + 改角色

    @Test
    @DisplayName("加人：新成员立刻可读可发；重复加人回 already_members（幂等）；ADMIN 也能加、MEMBER 不能")
    void addMembersAndPromote() {
        Account bob = newAccount("it_mem_b1_" + SUFFIX);
        Account carol = newAccount("it_mem_c1_" + SUFFIX);
        Account dave = newAccount("it_mem_d1_" + SUFFIX);
        long convId = newGroup("加人 " + SUFFIX, alice, bob);

        // ---------- 加人 ----------
        Map<String, Object> added = ok(post("/v1/conversations/" + convId + "/members",
                Map.of("members", List.of("@" + carol.handle())), alice.token()));
        assertSnakeCase(added, "conv_id", "added", "already_members", "member_count");
        assertThat(number(added.get("conv_id"))).isEqualTo(convId);
        assertThat(number(added.get("member_count"))).isEqualTo(3L);
        assertThat(added.get("already_members")).isEqualTo(List.of());
        Map<String, Object> carolRow = map(list(added.get("added")).get(0));
        assertSnakeCase(carolRow, "actor_id", "actor_type", "handle", "display_name", "avatar_url",
                "role", "joined_at");
        assertThat(number(carolRow.get("actor_id")))
                .as("回完整成员视图的意义就在这里：客户端手里只有发出去的 handle，"
                        + "而后续的踢人/改角色要的是 actor_id")
                .isEqualTo(carol.actorId());
        assertThat(carolRow.get("handle")).isEqualTo(carol.handle());
        assertThat(number(carolRow.get("role"))).as("新成员是 3=MEMBER").isEqualTo(3L);

        // 新成员立刻能看详情、能发消息（成员身份是权限的唯一依据）
        assertThat(number(ok(get("/v1/conversations/" + convId, carol.token())).get("member_count")))
                .isEqualTo(3L);
        ok(post("/v1/conversations/" + convId + "/messages",
                Map.of("msg_type", "TEXT", "client_msg_id", "it-mem-1", "content", Map.of("text", "大家好")),
                carol.token()));

        // ---------- 重复加人：幂等，不算错 ----------
        Map<String, Object> again = ok(post("/v1/conversations/" + convId + "/members",
                Map.of("members", List.of("@" + carol.handle())), alice.token()));
        assertThat(list(again.get("added"))).isEmpty();
        assertThat(list(again.get("already_members")))
                .as("被跳过的人要列出来，客户端据此刷新成员列表")
                .extracting(ConversationMemberHttpIT::number)
                .containsExactly(carol.actorId());
        assertThat(number(again.get("member_count"))).as("人数不该变").isEqualTo(3L);

        // ---------- 提为 ADMIN，然后由 ADMIN 加人 ----------
        Map<String, Object> promoted = ok(patch("/v1/conversations/" + convId + "/members/"
                + carol.actorId(), Map.of("role", 2), alice.token()));
        assertSnakeCase(promoted, "conv_id", "actor_id", "role", "owner_actor", "member_count");
        assertThat(number(promoted.get("role"))).isEqualTo(2L);
        assertThat(number(promoted.get("owner_actor"))).as("角色变了，群主没变").isEqualTo(alice.actorId());

        Map<String, Object> byAdmin = ok(post("/v1/conversations/" + convId + "/members",
                Map.of("members", List.of("@" + dave.handle())), carol.token()));
        assertThat(number(byAdmin.get("member_count"))).isEqualTo(4L);

        // ---------- MEMBER 不能加人、不能改角色（40305 → HTTP 403） ----------
        ResponseEntity<Map<String, Object>> denied = post("/v1/conversations/" + convId + "/members",
                Map.of("members", List.of("@it_no_such_handle")), dave.token());
        assertThat(denied.getStatusCode().value()).as("40305 属于权限段，HTTP 是 403").isEqualTo(403);
        assertThat(number(body(denied).get("code"))).isEqualTo(40305L);

        ResponseEntity<Map<String, Object>> deniedRole = patch("/v1/conversations/" + convId + "/members/"
                + carol.actorId(), Map.of("role", 3), carol.token());
        assertThat(deniedRole.getStatusCode().value()).isEqualTo(403);
        assertThat(number(body(deniedRole).get("code")))
                .as("ADMIN 不能给别人改角色：否则群主指定的管理员会被另一个管理员撤掉")
                .isEqualTo(40305L);
    }

    @Test
    @DisplayName("加人/改角色产生的 SYSTEM 消息真的落库了（客户端渲染就靠 content 里的 action 与 handle）")
    void memberOperationsWriteSystemMessages() {
        Account bob = newAccount("it_mem_b2_" + SUFFIX);
        Account carol = newAccount("it_mem_c2_" + SUFFIX);
        long convId = newGroup("系统消息 " + SUFFIX, alice, bob);

        ok(post("/v1/conversations/" + convId + "/members",
                Map.of("members", List.of("@" + carol.handle())), alice.token()));
        ok(patch("/v1/conversations/" + convId + "/members/" + carol.actorId(),
                Map.of("role", 2), alice.token()));

        List<Map<String, Object>> systems = systemMessages(convId, alice.token());
        assertThat(systems).as("建群 1 条 + 加人 1 条 + 改角色 1 条").hasSize(3);
        // 历史是倒序（最新在前）
        Map<String, Object> roleChanged = map(systems.get(0).get("content"));
        assertThat(roleChanged.get("action")).isEqualTo("member_role_changed");
        assertThat(number(roleChanged.get("actor_id"))).isEqualTo(carol.actorId());
        assertThat(number(roleChanged.get("role"))).isEqualTo(2L);
        assertThat(roleChanged.get("handle")).as("名字要带上：消息是历史，而人可能已经不在群里了")
                .isEqualTo(carol.handle());

        Map<String, Object> joined = map(systems.get(1).get("content"));
        assertThat(joined.get("action")).isEqualTo("member_joined");
        assertThat(number(joined.get("actor_id"))).isEqualTo(carol.actorId());
        assertThat(number(systems.get(1).get("sender_id")))
                .as("发起人是邀请者，不是被邀请的人")
                .isEqualTo(alice.actorId());

        assertThat(map(systems.get(2).get("content")).get("action")).isEqualTo("group_created");

        // 幂等：同一个值再设一次不产生第二条消息
        ok(patch("/v1/conversations/" + convId + "/members/" + carol.actorId(),
                Map.of("role", 2), alice.token()));
        assertThat(systemMessages(convId, alice.token())).hasSize(3);
    }

    // ================================================================ 转让群主 / 踢人 / 退群

    @Test
    @DisplayName("转让群主：role=1 把 owner_actor 一起改掉；旧群主降 ADMIN 后仍能踢人，但不能再改角色")
    void transferOwnershipAndKick() {
        Account bob = newAccount("it_mem_b3_" + SUFFIX);
        Account carol = newAccount("it_mem_c3_" + SUFFIX);
        long convId = newGroup("转让 " + SUFFIX, alice, bob);

        Map<String, Object> transferred = ok(patch("/v1/conversations/" + convId + "/members/"
                + bob.actorId(), Map.of("role", 1), alice.token()));
        assertThat(number(transferred.get("role"))).as("1=OWNER").isEqualTo(1L);
        assertThat(number(transferred.get("owner_actor")))
                .as("响应必须让调用者知道自己不再是群主了")
                .isEqualTo(bob.actorId());

        Map<String, Object> detail = ok(get("/v1/conversations/" + convId, alice.token()));
        assertThat(number(detail.get("owner_actor"))).isEqualTo(bob.actorId());
        assertThat(roleOf(detail, alice.actorId())).as("旧群主降为 ADMIN").isEqualTo(2L);
        assertThat(roleOf(detail, bob.actorId())).isEqualTo(1L);
        assertThat(map(systemMessages(convId, alice.token()).get(0).get("content")).get("action"))
                .isEqualTo("owner_transferred");

        // 旧群主（现在是 ADMIN）还能踢普通成员……
        ok(post("/v1/conversations/" + convId + "/members",
                Map.of("members", List.of("@" + carol.handle())), bob.token()));
        Map<String, Object> removed = ok(remove("/v1/conversations/" + convId + "/members/"
                + carol.actorId(), alice.token()));
        assertSnakeCase(removed, "conv_id", "actor_id", "member_count");
        assertThat(number(removed.get("actor_id"))).isEqualTo(carol.actorId());
        assertThat(number(removed.get("member_count"))).isEqualTo(2L);

        // ……但不能改别人的角色，也不能踢群主
        assertThat(number(body(patch("/v1/conversations/" + convId + "/members/" + bob.actorId(),
                Map.of("role", 2), alice.token())).get("code"))).isEqualTo(40305L);
        ResponseEntity<Map<String, Object>> kickOwner =
                remove("/v1/conversations/" + convId + "/members/" + bob.actorId(), alice.token());
        assertThat(kickOwner.getStatusCode().value()).isEqualTo(403);
        assertThat(number(body(kickOwner).get("code"))).isEqualTo(40305L);

        // 被踢的人立刻失去访问权（40303）
        assertThat(number(body(get("/v1/conversations/" + convId, carol.token())).get("code")))
                .isEqualTo(40303L);
    }

    @Test
    @DisplayName("退群：普通成员能退（并留下 member_left）；群主回 40306 且什么都没发生")
    void leavingAGroup() {
        Account bob = newAccount("it_mem_b4_" + SUFFIX);
        Account carol = newAccount("it_mem_c4_" + SUFFIX);
        long convId = newGroup("退群 " + SUFFIX, alice, bob);
        ok(post("/v1/conversations/" + convId + "/members",
                Map.of("members", List.of("@" + carol.handle())), alice.token()));

        // 群主先试：40306，而且是 HTTP 403（权限段），不是 200 里的一个失败码
        ResponseEntity<Map<String, Object>> ownerLeave =
                remove("/v1/conversations/" + convId + "/members/me", alice.token());
        assertThat(ownerLeave.getStatusCode().value()).isEqualTo(403);
        assertThat(number(body(ownerLeave).get("code"))).isEqualTo(40306L);
        assertThat(number(ok(get("/v1/conversations/" + convId, alice.token())).get("member_count")))
                .as("被拒的请求不该改任何东西").isEqualTo(3L);

        Map<String, Object> left = ok(remove("/v1/conversations/" + convId + "/members/me",
                carol.token()));
        assertThat(number(left.get("actor_id"))).as("路径里是 me，响应里给出具体是谁").isEqualTo(carol.actorId());
        assertThat(number(left.get("member_count"))).isEqualTo(2L);

        // 通知真的写进去了：退群的人已经不在成员表里，所以名字只能来自消息本身
        Map<String, Object> content = map(systemMessages(convId, alice.token()).get(0).get("content"));
        assertThat(content.get("action")).isEqualTo("member_left");
        assertThat(content.get("handle")).isEqualTo(carol.handle());

        // 退出之后：拉历史回 40303
        assertThat(number(body(get("/v1/conversations/" + convId + "/messages", carol.token())).get("code")))
                .isEqualTo(40303L);
    }

    // ================================================================ 改群名

    @Test
    @DisplayName("改群名：PATCH 生效、同名幂等、MEMBER 回 40305、超长回 40002（不截断）")
    void renamingAGroup() {
        Account bob = newAccount("it_mem_b5_" + SUFFIX);
        Account carol = newAccount("it_mem_c5_" + SUFFIX);
        long convId = newGroup("旧群名 " + SUFFIX, alice, bob, carol);

        Map<String, Object> renamed = ok(patch("/v1/conversations/" + convId,
                Map.of("title", "新群名 " + SUFFIX), alice.token()));
        assertSnakeCase(renamed, "conv_id", "title");
        assertThat(renamed.get("title")).isEqualTo("新群名 " + SUFFIX);
        assertThat(ok(get("/v1/conversations/" + convId, bob.token())).get("title"))
                .isEqualTo("新群名 " + SUFFIX);

        int messagesAfterRename = systemMessages(convId, alice.token()).size();
        ok(patch("/v1/conversations/" + convId, Map.of("title", "新群名 " + SUFFIX), alice.token()));
        assertThat(systemMessages(convId, alice.token()))
                .as("同名重复上报不该再写一条系统消息")
                .hasSize(messagesAfterRename);
        assertThat(map(systemMessages(convId, alice.token()).get(0).get("content")).get("action"))
                .isEqualTo("title_changed");

        ResponseEntity<Map<String, Object>> byMember = patch("/v1/conversations/" + convId,
                Map.of("title", "别人的群"), carol.token());
        assertThat(byMember.getStatusCode().value()).isEqualTo(403);
        assertThat(number(body(byMember).get("code"))).isEqualTo(40305L);

        ResponseEntity<Map<String, Object>> tooLong = patch("/v1/conversations/" + convId,
                Map.of("title", "x".repeat(129)), alice.token());
        assertThat(tooLong.getStatusCode().value()).as("40002 是业务码，HTTP 200").isEqualTo(200);
        assertThat(number(body(tooLong).get("code"))).isEqualTo(40002L);
        assertThat(ok(get("/v1/conversations/" + convId, alice.token())).get("title"))
                .as("超长的群名不该被截断着存进去")
                .isEqualTo("新群名 " + SUFFIX);
    }

    // ================================================================ 错误码与边界

    @Test
    @DisplayName("§4.9 的错误码：40402 / 40303 / 40904 / 40908 / 40906 / 40001 / 40002 / 40302")
    void memberOperationsUseDistinctErrorCodes() {
        Account bob = newAccount("it_mem_b6_" + SUFFIX);
        Account carol = newAccount("it_mem_c6_" + SUFFIX);
        Account dave = newAccount("it_mem_d6_" + SUFFIX);
        Account eve = newAccount("it_mem_e6_" + SUFFIX);
        Account outsider = newAccount("it_mem_x6_" + SUFFIX);
        long convId = newGroup("错误码 " + SUFFIX, alice, bob, carol);

        // 会话不存在：40402（不是 404 HTTP）
        ResponseEntity<Map<String, Object>> missing =
                post("/v1/conversations/999999999999/members", Map.of("members", List.of("@" + dave.handle())),
                        alice.token());
        assertThat(missing.getStatusCode().value()).isEqualTo(200);
        assertThat(number(body(missing).get("code"))).isEqualTo(40402L);

        // 外人：40303
        assertThat(number(body(post("/v1/conversations/" + convId + "/members",
                Map.of("members", List.of("@" + dave.handle())), outsider.token())).get("code")))
                .isEqualTo(40303L);

        // 目标不在群里：40908（HTTP 200 —— 它是状态类冲突，不是权限问题）
        ResponseEntity<Map<String, Object>> notMember =
                remove("/v1/conversations/" + convId + "/members/" + eve.actorId(), alice.token());
        assertThat(notMember.getStatusCode().value()).isEqualTo(200);
        assertThat(number(body(notMember).get("code"))).isEqualTo(40908L);

        // 对自己操作：40904
        assertThat(number(body(patch("/v1/conversations/" + convId + "/members/" + alice.actorId(),
                Map.of("role", 3), alice.token())).get("code"))).isEqualTo(40904L);
        assertThat(number(body(remove("/v1/conversations/" + convId + "/members/" + alice.actorId(),
                alice.token())).get("code")))
                .as("踢自己也该走 /members/me").isEqualTo(40904L);

        // 参数：空 members 40001、role 越界 40002、role 类型不符 40000
        assertThat(number(body(post("/v1/conversations/" + convId + "/members",
                Map.of("members", List.of()), alice.token())).get("code"))).isEqualTo(40001L);
        assertThat(number(body(patch("/v1/conversations/" + convId + "/members/" + bob.actorId(),
                Map.of("role", 4), alice.token())).get("code"))).isEqualTo(40002L);
        assertThat(number(body(post("/v1/conversations/" + convId + "/members",
                Map.of(), alice.token())).get("code")))
                .as("members 字段缺失也是 40001（与空数组同一个码：都是「没给我要加的人」）")
                .isEqualTo(40001L);
        assertThat(number(body(patch("/v1/conversations/" + convId + "/members/" + bob.actorId(),
                Map.of("role", "ADMIN"), alice.token())).get("code")))
                .as("类型不符是「请求体结构不对」（40000），不是「取值非法」（40002）")
                .isEqualTo(40000L);
        assertThat(number(body(patch("/v1/conversations/" + convId + "/members/" + bob.actorId(),
                Map.of(), alice.token())).get("code"))).isEqualTo(40001L);

        // 人数上限：4 人满 → 40906（本类把上限调成了 4，见类注释）
        assertThat(number(ok(post("/v1/conversations/" + convId + "/members",
                Map.of("members", List.of("@" + dave.handle())), alice.token())).get("member_count")))
                .isEqualTo(4L);
        ResponseEntity<Map<String, Object>> full = post("/v1/conversations/" + convId + "/members",
                Map.of("members", List.of("@" + eve.handle())), alice.token());
        assertThat(full.getStatusCode().value()).isEqualTo(200);
        assertThat(number(body(full).get("code"))).isEqualTo(40906L);

        // 单聊：这五个接口一律不可用（40302 —— 请求合法，是这个资源不支持这个操作）
        long direct = number(ok(post("/v1/conversations/direct",
                Map.of("peer", "@" + bob.handle()), alice.token())).get("conv_id"));
        createdConversations.add(direct);
        assertThat(number(body(post("/v1/conversations/" + direct + "/members",
                Map.of("members", List.of("@" + carol.handle())), alice.token())).get("code")))
                .isEqualTo(40302L);
        assertThat(number(body(remove("/v1/conversations/" + direct + "/members/" + bob.actorId(),
                alice.token())).get("code"))).isEqualTo(40302L);
        assertThat(number(body(remove("/v1/conversations/" + direct + "/members/me",
                alice.token())).get("code"))).isEqualTo(40302L);
        assertThat(number(body(patch("/v1/conversations/" + direct, Map.of("title", "改个名"),
                alice.token())).get("code"))).isEqualTo(40302L);
        assertThat(number(body(patch("/v1/conversations/" + direct + "/members/" + bob.actorId(),
                Map.of("role", 2), alice.token())).get("code"))).isEqualTo(40302L);
    }

    // ================================================================ 工具

    /** 建群并登记到清理列表。 */
    private long newGroup(String title, Account owner, Account... members) {
        List<String> refs = new ArrayList<>();
        for (Account member : members) {
            refs.add("@" + member.handle());
        }
        long convId = number(ok(post("/v1/conversations/group",
                Map.of("title", title, "members", refs), owner.token())).get("conv_id"));
        createdConversations.add(convId);
        return convId;
    }

    /** 拉历史并按 seq 倒序取全部 SYSTEM 消息（最新在前）。 */
    private List<Map<String, Object>> systemMessages(long convId, String token) {
        Map<String, Object> page = ok(get("/v1/conversations/" + convId + "/messages", token));
        return list(page.get("items")).stream()
                .map(ConversationMemberHttpIT::map)
                .filter(row -> "SYSTEM".equals(row.get("msg_type")))
                .toList();
    }

    /** 在会话详情的成员列表里取某个人的角色码。 */
    private static long roleOf(Map<String, Object> detail, long actorId) {
        return list(detail.get("members")).stream()
                .map(ConversationMemberHttpIT::map)
                .filter(row -> number(row.get("actor_id")) == actorId)
                .map(row -> number(row.get("role")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("成员列表里没有 actor_id=" + actorId));
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
        return rest.exchange(url(path), HttpMethod.POST, entity(body, bearer), JSON_MAP);
    }

    private ResponseEntity<Map<String, Object>> patch(String path, Object body, String bearer) {
        return rest.exchange(url(path), HttpMethod.PATCH, entity(body, bearer), JSON_MAP);
    }

    private ResponseEntity<Map<String, Object>> remove(String path, String bearer) {
        return rest.exchange(url(path), HttpMethod.DELETE, new HttpEntity<>(headers(bearer)), JSON_MAP);
    }

    private HttpEntity<Object> entity(Object body, String bearer) {
        return new HttpEntity<>(body, headers(bearer));
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
     * 断言信封里的字段名是 snake_case，并拒绝任何含大写字母的键。
     *
     * <p>与 {@code ConversationHttpIT} 同一套断言（那边的注释解释了为什么不能只查点名字段）：
     * 新增的响应体最容易在命名策略上出问题，而症状是「客户端解析出一片 null」。
     */
    private static void assertSnakeCase(Map<String, Object> data, String... expectedKeys) {
        assertThat(data.keySet()).contains(expectedKeys);
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
