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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 广场链路的端到端验证（03-rest-api.md §6）—— <b>真实 HTTP + 真实 MySQL/Redis</b>。
 *
 * <p><b>为什么必须有它</b>（单测覆盖不到的五件事）：
 * <ol>
 *   <li><b>DDL 真的与代码对得上</b>：新增的 {@code post.like_count}/{@code client_post_id}、
 *       {@code post_like} / {@code post_comment} 两张表都要真的存在——本地内存替身
 *       永远不会告诉你「库还没迁移」；</li>
 *   <li><b>主键/唯一索引真的是那几条防线</b>：重复点赞靠 {@code PRIMARY KEY (post_id, actor_id)}
 *       撞出来（40907），发帖重放靠 {@code uk_post_idem} 撞出来。内存替身里的
 *       「查重再插入」证明不了它们；</li>
 *   <li><b>计数的原子加减</b>（{@code like_count = like_count + 1}）在真实 SQL 下有效——
 *       写成「读出来 +1 再写回」在单线程替身里同样是绿的；</li>
 *   <li><b>分页游标的谓词与索引</b>：好友段走 {@code feed_item} 主键前缀、
 *       公开段走 {@code idx_visibility_time}，翻页不重不漏（含「同一毫秒」的边界）；</li>
 *   <li><b>写扩散真的落在了别人的收件箱里</b>（异步线程池 + 事务提交之后才跑那条路径）。</li>
 * </ol>
 *
 * <p>数据卫生：handle 带随机后缀；结束时按 handle 删 actor/actor_secret，
 * 并删掉与这些 actor 相关的 {@code post}/{@code post_like}/{@code post_comment}/{@code feed_item}
 * （{@code tools/clean_it_leftovers.py} 也认同一套判据）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlazaHttpIT {

    private static final String JWT_SECRET = "it-jwt-secret-0123456789abcdef-32B";
    private static final String PASSWORD = "it-pass-12345678";
    private static final String SUFFIX = Long.toHexString(System.nanoTime() & 0xFFFFFF);

    private static final String ALICE = "it_pz_alice_" + SUFFIX;

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
    private final Set<Long> ownedActorIds = new HashSet<>();
    private final Set<String> refreshTokens = new HashSet<>();

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
        registry.add("tm.node.id", () -> "it-plaza-boot");
        registry.add("tm.node.ttl", () -> "5m");
        registry.add("tm.netty.port", () -> "0");
        // 写扩散必须<b>同步完成</b>才验得了「动态进了好友的收件箱」：
        // 把线程数调大不会让异步变同步，所以这里换的是「等一会儿」而不是线程池。
        // 真异步链路由 PlazaServiceTest 的线程池用例覆盖。
    }

    @BeforeAll
    void setUp() {
        alice = newAccount(ALICE);
    }

    @AfterAll
    void cleanUp() {
        for (long actorId : ownedActorIds) {
            // feed_item 的主键是 (owner_id, score, post_id)：按 owner_id 与 author_id 各删一遍，
            // 「自己那行」与「好友那行」的 owner/author 恰好互补。
            jdbc.update("DELETE FROM feed_item WHERE owner_id = ? OR author_id = ?", actorId, actorId);
            jdbc.update("DELETE FROM post_like WHERE actor_id = ?", actorId);
            jdbc.update("DELETE FROM post_comment WHERE author_id = ?", actorId);
            jdbc.update("DELETE FROM post WHERE author_id = ?", actorId);
            jdbc.update("DELETE FROM friendship WHERE actor_a = ? OR actor_b = ?", actorId, actorId);
        }
        for (String handle : handles) {
            actors.findByHandle(handle).ifPresent(actor -> {
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
    @DisplayName("发帖 → 好友的信息流里有它（is_friend_author=true）→ 陌生人只从公开流看到它")
    void publishThenAppearInFriendFeed() {
        Account bob = newAccount("it_pz_b1_" + SUFFIX);
        Account carol = newAccount("it_pz_c1_" + SUFFIX);
        becomeFriends(alice, bob);

        Map<String, Object> created = ok(post("/v1/plaza/posts",
                Map.of("content", Map.of("text", "今天天气不错")), alice.token()));
        assertSnakeCase(created, "post_id", "author_id", "content", "visibility", "created_at");
        long postId = number(created.get("post_id"));
        assertThat(number(created.get("author_id"))).isEqualTo(alice.actorId());
        assertThat(number(created.get("visibility"))).isEqualTo(1L);
        Map<String, Object> content = map(created.get("content"));
        assertThat(content.get("text")).isEqualTo("今天天气不错");

        // 好友的信息流：第一条就是它，且 is_friend_author=true、作者字段齐全。
        // 注意：写扩散是<b>异步</b>的（发帖事务提交之后才跑），所以这里要等一小会儿——
        // 这不是「测试不稳定的妥协」，而是那个设计的可观测后果。
        Map<String, Object> first = awaitFeedItem(bob.token(), postId);
        assertSnakeCase(first, "post_id", "author", "content", "visibility", "is_friend_author",
                "created_at", "like_count", "comment_count", "liked_by_me");
        assertThat(first.get("is_friend_author")).isEqualTo(true);
        assertThat(first.get("liked_by_me")).isEqualTo(false);
        assertThat(number(first.get("like_count"))).isZero();
        assertThat(number(first.get("comment_count"))).isZero();
        Map<String, Object> author = map(first.get("author"));
        assertSnakeCase(author, "actor_id", "actor_type", "handle", "display_name", "avatar_url");
        assertThat(number(author.get("actor_id"))).isEqualTo(alice.actorId());

        // 作者自己也能在自己的信息流里看到它（否则「发完就消失」），但不是「好友来源」
        Map<String, Object> own = awaitFeedItem(alice.token(), postId);
        assertThat(own.get("is_friend_author")).as("自己的动态不是好友来源").isEqualTo(false);

        // 陌生人（非好友）从公开流里看到它，且 is_friend_author=false
        Map<String, Object> strangerView = awaitFeedItem(carol.token(), postId);
        assertThat(strangerView.get("is_friend_author")).isEqualTo(false);

        // 某人的动态（§6.3）
        Map<String, Object> authorPosts = ok(get("/v1/plaza/users/" + alice.actorId() + "/posts", bob.token()));
        assertThat(list(authorPosts.get("items"))).extracting(item -> number(map(item).get("post_id")))
                .contains(postId);

        // 库里真的落了 content JSON 与计数列
        assertThat(jdbc.queryForObject("SELECT content FROM post WHERE id = ?", String.class, postId))
                .contains("今天天气不错");
        assertThat(jdbc.queryForObject("SELECT like_count FROM post WHERE id = ?", Integer.class, postId))
                .isZero();
    }

    @Test
    @DisplayName("发帖幂等：带同一个 client_post_id 重放拿到同一个 post_id，库里只有一行、只扩散一次")
    void publishIsIdempotentByClientPostId() {
        // 用一个全新的账号：alice 在本类的另一些用例里已经有好友了，
        // 而「收件箱里有几行」这个断言只有在候选集确定时才有意义（同 FriendHttpIT 的取舍）。
        Account solo = newAccount("it_pz_solo_" + SUFFIX);
        String key = "it-pz-" + SUFFIX + "-1";
        Map<String, Object> first = ok(post("/v1/plaza/posts",
                Map.of("content", Map.of("text", "草稿"), "client_post_id", key), solo.token()));
        Map<String, Object> replay = ok(post("/v1/plaza/posts",
                Map.of("content", Map.of("text", "草稿"), "client_post_id", key), solo.token()));

        long postId = number(first.get("post_id"));
        assertThat(number(replay.get("post_id"))).isEqualTo(postId);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM post WHERE author_id = ? AND client_post_id = ?",
                Integer.class, solo.actorId(), key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM feed_item WHERE post_id = ?",
                Integer.class, postId))
                .as("没有好友时只应该有作者自己那一行——重放不该再扩散一次")
                .isEqualTo(1);

        // 同一个账号、不同的幂等键 → 两条动态（幂等键是客户端给的语义，不是服务端的去重规则）
        long second = number(ok(post("/v1/plaza/posts",
                Map.of("content", Map.of("text", "草稿"), "client_post_id", key + "-b"),
                solo.token())).get("post_id"));
        assertThat(second).isNotEqualTo(postId);
    }

    @Test
    @DisplayName("可见性：FRIENDS_ONLY 的好友看得到、陌生人看不到（在信息流与个人页两处都是）")
    void friendsOnlyVisibility() {
        Account bob = newAccount("it_pz_b2_" + SUFFIX);
        Account carol = newAccount("it_pz_c2_" + SUFFIX);
        becomeFriends(alice, bob);
        long postId = number(ok(post("/v1/plaza/posts",
                Map.of("content", Map.of("text", "只给好友看"), "visibility", "FRIENDS_ONLY"),
                alice.token())).get("post_id"));

        // 先用「好友能看到」把异步扩散等完，再断言「陌生人看不到」——
        // 否则那条否定断言可能在扩散完成之前就通过了（它测试的是別人的时序）。
        awaitFeedItem(bob.token(), postId);
        assertThat(postIds(ok(get("/v1/plaza/feed?limit=50", carol.token()))))
                .as("陌生人的信息流里不该出现仅好友可见的动态")
                .doesNotContain(postId);
        assertThat(postIds(ok(get("/v1/plaza/users/" + alice.actorId() + "/posts", carol.token()))))
                .as("陌生人在个人页也看不到它")
                .doesNotContain(postId);
        assertThat(postIds(ok(get("/v1/plaza/users/" + alice.actorId() + "/posts", bob.token()))))
                .contains(postId);

        // 陌生人给它点赞 → 40302（HTTP 403）
        ResponseEntity<Map<String, Object>> denied = post(
                "/v1/plaza/posts/" + postId + "/like", Map.of(), carol.token());
        assertThat(denied.getStatusCode().value()).isEqualTo(403);
        assertThat(number(body(denied).get("code"))).isEqualTo(40302L);
    }

    @Test
    @DisplayName("点赞：计数 +1（真实 SQL 的原子加减）、重复点赞 40907、取消幂等")
    void likeRoundTrip() {
        Account bob = newAccount("it_pz_b3_" + SUFFIX);
        long postId = number(ok(post("/v1/plaza/posts",
                Map.of("content", Map.of("text", "来点赞")), alice.token())).get("post_id"));

        Map<String, Object> liked = ok(post("/v1/plaza/posts/" + postId + "/like", Map.of(), bob.token()));
        assertSnakeCase(liked, "post_id", "like_count", "liked_by_me");
        assertThat(number(liked.get("like_count"))).isEqualTo(1L);
        assertThat(liked.get("liked_by_me")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT like_count FROM post WHERE id = ?", Integer.class, postId))
                .isEqualTo(1);

        // 重复点赞：主键冲突 → 40907，且计数不变（这条只有真实索引能验）
        assertThat(number(body(post("/v1/plaza/posts/" + postId + "/like", Map.of(), bob.token()))
                .get("code"))).isEqualTo(40907L);
        assertThat(jdbc.queryForObject("SELECT like_count FROM post WHERE id = ?", Integer.class, postId))
                .as("重复点赞不能把计数加两次")
                .isEqualTo(1);

        // 取消：计数 -1；再取消一次幂等且不会变成负数
        Map<String, Object> unliked = ok(remove("/v1/plaza/posts/" + postId + "/like", bob.token()));
        assertThat(number(unliked.get("like_count"))).isZero();
        assertThat(unliked.get("liked_by_me")).isEqualTo(false);
        ok(remove("/v1/plaza/posts/" + postId + "/like", bob.token()));
        assertThat(jdbc.queryForObject("SELECT like_count FROM post WHERE id = ?", Integer.class, postId))
                .isZero();

        // 信息流里的 liked_by_me 反映的是调用者
        ok(post("/v1/plaza/posts/" + postId + "/like", Map.of(), bob.token()));
        Map<String, Object> bobFeed = ok(get("/v1/plaza/feed?limit=50", bob.token()));
        Map<String, Object> item = map(list(bobFeed.get("items")).stream()
                .map(PlazaHttpIT::map)
                .filter(row -> number(row.get("post_id")) == postId)
                .findFirst().orElseThrow());
        assertThat(item.get("liked_by_me")).isEqualTo(true);
        assertThat(number(item.get("like_count"))).isEqualTo(1L);
    }

    @Test
    @DisplayName("评论：写入 + 计数 +1、列表正序、回复跨动态 40002、删除权限（评论作者或动态作者）")
    void commentRoundTrip() {
        Account bob = newAccount("it_pz_b4_" + SUFFIX);
        long postId = number(ok(post("/v1/plaza/posts",
                Map.of("content", Map.of("text", "来评论")), alice.token())).get("post_id"));

        Map<String, Object> first = ok(post("/v1/plaza/posts/" + postId + "/comments",
                Map.of("content", "确实不错"), bob.token()));
        assertSnakeCase(first, "comment_id", "post_id", "author", "content", "reply_to_comment_id",
                "created_at");
        long firstId = number(first.get("comment_id"));
        assertThat(first.get("reply_to_comment_id")).isNull();

        Map<String, Object> second = ok(post("/v1/plaza/posts/" + postId + "/comments",
                Map.of("content", "回复楼上", "reply_to_comment_id", firstId), bob.token()));
        assertThat(number(second.get("reply_to_comment_id"))).isEqualTo(firstId);
        assertThat(jdbc.queryForObject("SELECT comment_count FROM post WHERE id = ?", Integer.class, postId))
                .isEqualTo(2);

        Map<String, Object> listed = ok(get("/v1/plaza/posts/" + postId + "/comments?limit=50", alice.token()));
        assertSnakeCase(listed, "items", "next_cursor", "has_more");
        assertThat(list(listed.get("items"))).extracting(row -> number(map(row).get("comment_id")))
                .as("评论按时间正序（一条讨论线从上往下读）")
                .containsExactly(firstId, number(second.get("comment_id")));

        // 回复一条别的动态的评论 → 40002
        long otherPost = number(ok(post("/v1/plaza/posts",
                Map.of("content", Map.of("text", "另一条")), alice.token())).get("post_id"));
        assertThat(number(body(post("/v1/plaza/posts/" + otherPost + "/comments",
                Map.of("content", "跨动态回复", "reply_to_comment_id", firstId), alice.token()))
                .get("code"))).isEqualTo(40002L);

        // 第三方删不了（40302）；动态作者能删；删完计数 -1
        Account carol = newAccount("it_pz_c3_" + SUFFIX);
        ResponseEntity<Map<String, Object>> forbidden = remove(
                "/v1/plaza/comments/" + firstId, carol.token());
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
        assertThat(number(body(forbidden).get("code"))).isEqualTo(40302L);

        Map<String, Object> deleted = ok(remove("/v1/plaza/comments/" + firstId, alice.token()));
        assertSnakeCase(deleted, "comment_id", "deleted");
        assertThat(deleted.get("deleted")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT comment_count FROM post WHERE id = ?", Integer.class, postId))
                .isEqualTo(1);

        // 评论作者自己也能删
        ok(remove("/v1/plaza/comments/" + number(second.get("comment_id")), bob.token()));
        assertThat(jdbc.queryForObject("SELECT comment_count FROM post WHERE id = ?", Integer.class, postId))
                .isZero();
    }

    @Test
    @DisplayName("删除动态：非作者 40302；作者删掉之后动态、收件箱、点赞、评论一起清掉，再操作是 40404")
    void deleteRemovesEverything() {
        Account bob = newAccount("it_pz_b5_" + SUFFIX);
        becomeFriends(alice, bob);
        long postId = number(ok(post("/v1/plaza/posts",
                Map.of("content", Map.of("text", "要删的")), alice.token())).get("post_id"));
        ok(post("/v1/plaza/posts/" + postId + "/like", Map.of(), bob.token()));
        ok(post("/v1/plaza/posts/" + postId + "/comments", Map.of("content", "别删"), bob.token()));

        ResponseEntity<Map<String, Object>> forbidden = remove("/v1/plaza/posts/" + postId, bob.token());
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
        assertThat(number(body(forbidden).get("code"))).isEqualTo(40302L);

        Map<String, Object> deleted = ok(remove("/v1/plaza/posts/" + postId, alice.token()));
        assertSnakeCase(deleted, "post_id", "deleted");
        assertThat(deleted.get("deleted")).isEqualTo(true);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post WHERE id = ?", Integer.class, postId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM feed_item WHERE post_id = ?",
                Integer.class, postId))
                .as("收件箱里的行必须一起清掉（idx_post 就是为这一步建的）")
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_like WHERE post_id = ?",
                Integer.class, postId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_comment WHERE post_id = ?",
                Integer.class, postId)).isZero();

        assertThat(number(body(post("/v1/plaza/posts/" + postId + "/like", Map.of(), alice.token()))
                .get("code"))).isEqualTo(40404L);
        assertThat(postIds(ok(get("/v1/plaza/feed?limit=50", bob.token())))).doesNotContain(postId);
    }

    // ================================================================ 分页（只有真实 SQL 才验得了）

    @Test
    @DisplayName("信息流分页：好友段在前、翻页不重不漏；好友段读完之后接公开段")
    void feedPaginatesWithoutGaps() {
        // 专用账号：这一类断言只在候选集确定时才有意义（alice 在本类另一些用例里已有内容）。
        // 注意公开段是全局的（非好友的公开动态），所以这里不断言「总共几条」，
        // 只断言「好友段恰好是那四条且在最前面」与「整段翻了不重不漏」。
        Account pager = newAccount("it_pz_pager_" + SUFFIX);
        Account friend = newAccount("it_pz_pf_" + SUFFIX);
        Account stranger = newAccount("it_pz_ps_" + SUFFIX);
        becomeFriends(pager, friend);

        Set<Long> friendBand = new HashSet<>();
        for (int i = 1; i <= 2; i++) {
            friendBand.add(number(ok(post("/v1/plaza/posts",
                    Map.of("content", Map.of("text", "pager-" + i)), pager.token())).get("post_id")));
            friendBand.add(number(ok(post("/v1/plaza/posts",
                    Map.of("content", Map.of("text", "friend-" + i)), friend.token())).get("post_id")));
            ok(post("/v1/plaza/posts", Map.of("content", Map.of("text", "stranger-" + i)),
                    stranger.token()));
        }

        Set<Long> seen = new HashSet<>();
        List<Long> order = new ArrayList<>();
        // 先等扩散把好友段填满：否则翻页断言会在「扩散还没跑」的时序下随机失败
        awaitFeedItems(pager.token(), friendBand);
        String cursor = null;
        boolean hasMore = true;
        int pages = 0;
        while (hasMore && pages++ < 20) {
            Map<String, Object> page = ok(get("/v1/plaza/feed?limit=2"
                    + (cursor == null ? "" : "&cursor=" + cursor), pager.token()));
            for (Object row : list(page.get("items"))) {
                long postId = number(map(row).get("post_id"));
                assertThat(seen.add(postId)).as("翻页出现了重复：postId=%s", postId).isTrue();
                order.add(postId);
            }
            cursor = (String) page.get("next_cursor");
            hasMore = Boolean.TRUE.equals(page.get("has_more"));
            if (hasMore) {
                assertThat(cursor).as("has_more=true 时游标不能为 null").isNotNull();
            }
        }
        assertThat(hasMore).as("翻完之后 has_more 必须变成 false").isFalse();
        assertThat(order).as("好友段（自己 + 好友各两条）永远不会缺").containsAll(friendBand);
        assertThat(new HashSet<>(order.subList(0, friendBand.size())))
                .as("好友段整体在公开段之前")
                .isEqualTo(friendBand);
    }

    @Test
    @DisplayName("游标贴错类型会被拒（40010）—— 会话/好友/信息流的游标不能互相贴")
    void wrongCursorTypeRejected() {
        String conversationCursor = "eyJ2IjoxLCJ0IjoiY29udiIsImF0IjoxNzY3MjI1NjAwMDAwLCJjaWQiOjF9";
        assertThat(number(body(get("/v1/plaza/feed?cursor=" + conversationCursor, alice.token()))
                .get("code"))).isEqualTo(40010L);

        // 取一个真实的信息流游标（pfeed），贴到「某人的动态」（要 ppost）上
        Map<String, Object> page = ok(get("/v1/plaza/feed?limit=1", alice.token()));
        String feedCursor = (String) page.get("next_cursor");
        if (feedCursor != null) {
            assertThat(number(body(get("/v1/plaza/users/" + alice.actorId()
                    + "/posts?cursor=" + feedCursor, alice.token())).get("code")))
                    .as("信息流游标（pfeed）不是「某人的动态」游标（ppost）")
                    .isEqualTo(40010L);
        }
        // 乱七八糟的字符串也不是游标
        assertThat(number(body(get("/v1/plaza/feed?cursor=not-a-cursor", alice.token())).get("code")))
                .isEqualTo(40010L);
    }

    // ================================================================ 错误码

    @Test
    @DisplayName("错误码：空动态 40001、正文超长 40006、visibility 写错 40002、不存在的动态 40404")
    void errorCodesOverHttp() {
        assertThat(number(body(post("/v1/plaza/posts", Map.of("content", Map.of()), alice.token()))
                .get("code"))).isEqualTo(40001L);
        assertThat(number(body(post("/v1/plaza/posts",
                Map.of("content", Map.of("text", "x".repeat(5001))), alice.token())).get("code")))
                .isEqualTo(40006L);
        assertThat(number(body(post("/v1/plaza/posts",
                Map.of("content", Map.of("text", "hi"), "visibility", "SOMETIMES"), alice.token())
        ).get("code"))).isEqualTo(40002L);
        assertThat(number(body(post("/v1/plaza/posts",
                Map.of("content", Map.of("images", List.of(Map.of("media_id", 1)))), alice.token())
        ).get("code")))
                .as("引用一张不存在的图 → 40008（先上传再引用）")
                .isEqualTo(40008L);

        assertThat(number(body(get("/v1/plaza/posts/999999999999/comments", alice.token()))
                .get("code"))).isEqualTo(40404L);
        ResponseEntity<Map<String, Object>> missing = remove("/v1/plaza/posts/999999999999", alice.token());
        assertThat(missing.getStatusCode().value())
                .as("不存在的动态是业务失败：HTTP 200 + 40404")
                .isEqualTo(200);
        assertThat(number(body(missing).get("code"))).isEqualTo(40404L);
    }

    // ================================================================ 工具

    /**
     * 等一条动态出现在某个人的信息流里，把它那一条返回。
     *
     * <p>写扩散是异步的（DESIGN §11.3：发帖只负责落库，扩散在提交之后跑），
     * 所以「好友立刻拉信息流」本来就可能晚于扩散完成——这不是缺陷，
     * 而是这条链路的可观测性质。测试要做的是等一个有界的上限，
     * 而不是把异步当成同步（那样要么假绿、要么随机器负载飘）。
     */
    private Map<String, Object> awaitFeedItem(String token, long postId) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            for (Object row : list(ok(get("/v1/plaza/feed?limit=50", token)).get("items"))) {
                Map<String, Object> item = map(row);
                if (number(item.get("post_id")) == postId) {
                    return item;
                }
            }
            sleep(100);
        }
        throw new AssertionError("15 秒内这条动态没有出现在信息流里（写扩散可能没跑）: postId=" + postId);
    }

    /** 等到这些动态都进了某个人的信息流（否则翻页断言会随机地少几条）。 */
    private void awaitFeedItems(String token, Set<Long> postIds) {
        long deadline = System.currentTimeMillis() + 15_000;
        Set<Long> missing = new HashSet<>(postIds);
        while (System.currentTimeMillis() < deadline) {
            missing = new HashSet<>(postIds);
            missing.removeAll(postIds(ok(get("/v1/plaza/feed?limit=50", token))));
            if (missing.isEmpty()) {
                return;
            }
            sleep(100);
        }
        throw new AssertionError("15 秒内这些动态没有全部进信息流: " + missing);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Set<Long> postIds(Map<String, Object> page) {
        Set<Long> ids = new HashSet<>();
        for (Object row : list(page.get("items"))) {
            ids.add(number(map(row).get("post_id")));
        }
        return ids;
    }

    /** 走真实路径成为好友（复用 §3 的接口，顺便验证两个模块的衔接）。 */
    private void becomeFriends(Account one, Account other) {
        long requestId = number(ok(post("/v1/friends/requests",
                Map.of("target", "@" + other.handle()), one.token())).get("request_id"));
        ok(post("/v1/friends/requests/" + requestId + "/accept", Map.of(), other.token()));
    }

    private Account newAccount(String handle) {
        handles.add(handle);
        Map<String, Object> data = ok(post("/v1/auth/register",
                Map.of("handle", handle, "password", PASSWORD, "display_name", handle), null));
        refreshTokens.add((String) data.get("refresh_token"));
        long actorId = number(data.get("actor_id"));
        ownedActorIds.add(actorId);
        return new Account(actorId, handle, (String) data.get("access_token"));
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
