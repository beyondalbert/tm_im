package com.tm.im.api.admin.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tm.im.api.admin.auth.CurrentAdminArgumentResolver;
import com.tm.im.api.admin.controller.AdminAccountController;
import com.tm.im.api.admin.controller.AdminActorController;
import com.tm.im.api.admin.controller.AdminAuditController;
import com.tm.im.api.admin.controller.AdminAuthController;
import com.tm.im.api.admin.controller.AdminContentController;
import com.tm.im.api.common.web.ApiExceptionHandler;
import com.tm.im.core.admin.AdminProperties;
import com.tm.im.core.admin.AdminService;
import com.tm.im.core.admin.InMemoryAdminStore;
import com.tm.im.core.agent.InMemoryAgentProfiles;
import com.tm.im.core.conversation.InMemoryActors;
import com.tm.im.core.plaza.InMemoryPosts;
import com.tm.im.core.plaza.PostDeletionPort;
import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.entity.AdminUser;
import com.tm.im.domain.entity.Post;
import com.tm.im.domain.enums.AdminRole;
import com.tm.im.domain.enums.AdminStatus;
import com.tm.im.domain.enums.Visibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台 REST 的 HTTP 层契约：信封、错误码、鉴权入口、参数校验、分页字段。
 *
 * <p><b>它补的是哪一块</b>：{@code AdminServiceTest} 验的是规则（谁能做什么、
 * 锁几次、审计写了什么），{@code AdminHttpIT} 验的是真装配 + 真库（扫描清单、
 * 封禁立刻生效、级联删除）。而这一层——{@code @CurrentAdmin} 解析器、
 * {@code AdminParams} 的码值转换、分页游标的编解码、snake_case 与时间格式——
 * 在两者之间：MockMvc 里能看到，真库上也看不全（它只跑几条路径）。
 *
 * <p><b>为什么用真的 {@code AdminService}</b>：控制器几乎不含判断，
 * 它把所有输入翻译成服务层调用。把一个 mock 服务塞进来，用例断言的将是
 * 「我 mock 了什么」，而不是「客户端会收到什么」——例如
 * {@code limit=1000} 被夹到 {@code max_page_size} 这条性质，
 * mock 永远不会告诉你它到底被夹了没有。
 *
 * <p>装配与 {@code ApiExceptionHandlerTest} 同源：standalone MockMvc +
 * 与生产同源不同物的 ObjectMapper（NON_NULL 会让 {@code data: null} 消失，
 * 于是「失败响应里 data 显式为 null」这条契约就验不了了）。
 */
class AdminApiContractTest {

    private static final String SUPER_USERNAME = "contract_super";
    private static final String OPS_USERNAME = "contract_ops";
    /** 口令长度必须过 {@code tm.admin.min-password-length}（默认 12）。 */
    private static final String PASSWORD = "contract-password-1";

    private InMemoryAdminStore store;
    private InMemoryActors actors;
    private InMemoryPosts posts;
    private List<Long> deletedPosts;
    private MockMvc mvc;
    private String superToken;
    private String opsToken;

    @BeforeEach
    void setUp() throws Exception {
        store = new InMemoryAdminStore();
        actors = new InMemoryActors();
        posts = new InMemoryPosts();
        deletedPosts = new ArrayList<>();

        AdminProperties properties = new AdminProperties();
        AdminService admins = new AdminService(store.users, store.sessions, store.audits,
                actors, new InMemoryAgentProfiles(), posts,
                postId -> deletedPosts.add(postId),
                store.idGenerator(), properties, ZoneId.of("Asia/Shanghai"));

        mvc = MockMvcBuilders
                .standaloneSetup(
                        new AdminAuthController(admins, ZoneId.of("Asia/Shanghai")),
                        new AdminActorController(admins, ZoneId.of("Asia/Shanghai")),
                        new AdminAccountController(admins, ZoneId.of("Asia/Shanghai")),
                        new AdminContentController(admins, ZoneId.of("Asia/Shanghai")),
                        new AdminAuditController(admins, ZoneId.of("Asia/Shanghai")))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new CurrentAdminArgumentResolver(admins))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper()))
                .build();

        store.users.put(SUPER_USERNAME, PASSWORD, AdminRole.SUPER, AdminStatus.ACTIVE);
        store.users.put(OPS_USERNAME, PASSWORD, AdminRole.OPS, AdminStatus.ACTIVE);
        superToken = login(SUPER_USERNAME);
        opsToken = login(OPS_USERNAME);
    }

    /**
     * 与生产同源的 ObjectMapper（见类注释）。生产那份由 Spring Boot 按
     * {@code application.yml} 的 {@code spring.jackson.*} 装配；
     * 「配置文件里的设置真的生效了吗」只由 {@code AdminHttpIT} 的真实 HTTP 回答。
     */
    private static ObjectMapper mapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .setSerializationInclusion(JsonInclude.Include.ALWAYS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // 从响应里取 token：不用测试自己的序列化替代品去反序列化，直接切字符串更接近客户端
        int at = body.indexOf("\"token\":\"");
        return body.substring(at + 9, body.indexOf('"', at + 9));
    }

    // ------------------------------------------------------------------ 登录

    @Test
    @DisplayName("登录：adm_ 前缀的会话明文只在这里出现一次，时间带 Z，响应里没有任何口令痕迹")
    void loginReturnsSessionOnce() throws Exception {
        mvc.perform(post("/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + SUPER_USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").value(startsWith("adm_")))
                .andExpect(jsonPath("$.data.expires_at").value(endsWith("Z")))
                // 会话明文与口令哈希都不该出现在响应里：前者只回一次，后者永远不回
                .andExpect(jsonPath("$.data.admin.username").value(SUPER_USERNAME))
                .andExpect(jsonPath("$.data.admin.role").value(AdminRole.SUPER.code()))
                .andExpect(content().string(not(containsString("pbkdf2"))))
                .andExpect(content().string(not(containsString(PASSWORD))));
    }

    @Test
    @DisplayName("登录：body 缺失或字段缺失一律 40001（不猜默认账号）")
    void loginRequiresBothFields() throws Exception {
        mvc.perform(post("/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001));
        mvc.perform(post("/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + SUPER_USERNAME + "\"}"))
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("登录失败：用户名不存在与口令不对回同一个 40101（后台接口不能当用户名枚举器）")
    void loginFailuresAreIndistinguishable() throws Exception {
        String wrongPassword = mvc.perform(post("/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + SUPER_USERNAME + "\",\"password\":\"nope-nope-nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101))
                .andReturn().getResponse().getContentAsString();
        String noSuchUser = mvc.perform(post("/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody_here\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(noSuchUser).isEqualTo(wrongPassword);
        // 失败也要留下审计行：否则「有人在撞口令」这件事在后台里看不见
        assertThat(store.audits.withAction(AdminService.ACTION_LOGIN_FAILED)).hasSize(1);
    }

    // ------------------------------------------------------------------ 鉴权

    @Test
    @DisplayName("鉴权入口：没头 40101（客户端该补请求头）；贴 JWT 回 40102（该换凭证）")
    void authenticationErrors() throws Exception {
        mvc.perform(get("/v1/admin/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));

        // 用户端的 JWT 形状：不是 adm_ 前缀 → 40102，而不是「口令不对」那条分支
        mvc.perform(get("/v1/admin/me")
                        .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.e30.signature"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40102));

        // 不是 Bearer 形式（例如直接把明文丢进头里）也是 40102
        mvc.perform(get("/v1/admin/me").header("Authorization", "adm_something"))
                .andExpect(jsonPath("$.code").value(40102));
    }

    @Test
    @DisplayName("登出幂等：没有凭证、或凭证早已失效，都回 200（否则客户端不敢清本地状态）")
    void logoutIsIdempotent() throws Exception {
        mvc.perform(post("/v1/admin/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        String temp = login(OPS_USERNAME);
        mvc.perform(post("/v1/admin/auth/logout").header("Authorization", "Bearer " + temp))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/admin/me").header("Authorization", "Bearer " + temp))
                .andExpect(jsonPath("$.code").value(40102));
        // 再登出一次：凭证已经不在了，仍然成功
        mvc.perform(post("/v1/admin/auth/logout").header("Authorization", "Bearer " + temp))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("会话过期回 40103（客户端据此提示重新登录，而不是「凭证格式错」）")
    void expiredSessionIs40103() throws Exception {
        String token = login(OPS_USERNAME);
        // 直接把库里的到期时间改到过去——真等 8 小时不是测试该做的事
        store.sessions.findByTokenHash(com.tm.im.common.crypto.OpaqueToken.hash(token))
                .orElseThrow().setExpiresAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(1));

        mvc.perform(get("/v1/admin/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40103));
    }

    // ------------------------------------------------------------------ 角色分级

    @Test
    @DisplayName("/v1/admin/me 对 OPS 也是 200：读「我是谁」不需要权限，前端靠它隐藏无权入口")
    void meIsAvailableToOps() throws Exception {
        mvc.perform(get("/v1/admin/me").header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(OPS_USERNAME))
                .andExpect(jsonPath("$.data.role").value(AdminRole.OPS.code()));
    }

    @Test
    @DisplayName("后台账号管理整组只有 SUPER 能调：OPS 一律 40302（403 而不是 404，见 07-errors-limits §2）")
    void accountManagementIsSuperOnly() throws Exception {
        mvc.perform(get("/v1/admin/accounts").header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40302));
        mvc.perform(post("/v1/admin/accounts")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"someone_else\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(jsonPath("$.code").value(40302));

        mvc.perform(get("/v1/admin/accounts").header("Authorization", "Bearer " + superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    @DisplayName("建号：口令太短或用户名不合规回 40002；重名回 40909")
    void createAdminValidates() throws Exception {
        mvc.perform(post("/v1/admin/accounts")
                        .header("Authorization", "Bearer " + superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"new_ops\",\"password\":\"short\"}"))
                .andExpect(jsonPath("$.code").value(40002));

        mvc.perform(post("/v1/admin/accounts")
                        .header("Authorization", "Bearer " + superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"No Spaces\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(jsonPath("$.code").value(40002));

        mvc.perform(post("/v1/admin/accounts")
                        .header("Authorization", "Bearer " + superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"new_ops\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value(AdminRole.OPS.code()));

        mvc.perform(post("/v1/admin/accounts")
                        .header("Authorization", "Bearer " + superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"new_ops\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(jsonPath("$.code").value(40909));
    }

    @Test
    @DisplayName("停用自己回 40904（防「手一抖把自己锁在门外」，那条路没有自助恢复）")
    void cannotDisableSelf() throws Exception {
        AdminUser me = store.users.findByUsername(SUPER_USERNAME).orElseThrow();
        mvc.perform(patch("/v1/admin/accounts/" + me.getId() + "/status")
                        .header("Authorization", "Bearer " + superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":2}"))
                .andExpect(jsonPath("$.code").value(40904));
    }

    // ------------------------------------------------------------------ 参与者

    @Test
    @DisplayName("参与者列表：码值越界回 40002（静默当成「不过滤」会让运营以为筛过了）")
    void actorFiltersValidateCodes() throws Exception {
        mvc.perform(get("/v1/admin/actors?actor_type=9")
                        .header("Authorization", "Bearer " + superToken))
                .andExpect(jsonPath("$.code").value(40002));
        mvc.perform(get("/v1/admin/actors?status=9")
                        .header("Authorization", "Bearer " + superToken))
                .andExpect(jsonPath("$.code").value(40002));
        // 不传就是不筛：这条必须是真的 200，否则上面两条可能只是「参数存在就报错」
        mvc.perform(get("/v1/admin/actors").header("Authorization", "Bearer " + superToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("分页：limit 被夹到 max_page_size（不是报错）、超出的一页只给 has_more 与游标")
    void paginationClampsAndCarriesCursor() throws Exception {
        // 一次性地造出「超过一页」的数据：120 行 > max_page_size(100)，
        // 于是 limit=1000 到底被夹成了什么，从 items 的长度上就看得出来。
        for (int i = 1; i <= 120; i++) {
            actors.put(7000L + i, String.format("contract_page_%03d", i));
        }

        mvc.perform(get("/v1/admin/actors?limit=1000&handle_prefix=contract_page_")
                        .header("Authorization", "Bearer " + superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(100))
                .andExpect(jsonPath("$.data.has_more").value(true));

        // 取 3 条：has_more=true，游标按最后一行生成
        String firstPage = mvc.perform(get("/v1/admin/actors?limit=3&handle_prefix=contract_page_")
                        .header("Authorization", "Bearer " + superToken))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.has_more").value(true))
                .andExpect(jsonPath("$.data.items[0].handle").value("contract_page_120"))
                .andReturn().getResponse().getContentAsString();
        String cursor = firstPage.substring(firstPage.indexOf("\"next_cursor\":\"") + 15);
        cursor = cursor.substring(0, cursor.indexOf('"'));

        // 第二页必须与第一页不重叠，且整体仍按 id 倒序
        String secondPage = mvc.perform(get("/v1/admin/actors?limit=3&handle_prefix=contract_page_"
                        + "&cursor=" + cursor)
                        .header("Authorization", "Bearer " + superToken))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[0].handle").value("contract_page_117"))
                .andReturn().getResponse().getContentAsString();
        assertThat(secondPage).doesNotContain("contract_page_120");

        // 游标改坏了要报 40010，而不是当成「第一页」从头再来
        mvc.perform(get("/v1/admin/actors?cursor=not-a-cursor")
                        .header("Authorization", "Bearer " + superToken))
                .andExpect(jsonPath("$.code").value(40010));
    }

    @Test
    @DisplayName("参与者详情：不存在回 40401；Agent 多一个 agent_profile，人没有（展示差异而非模型差异）")
    void actorDetailShowsAgentProfileOnly() throws Exception {
        Actor human = actors.put(7101L, "contract_human");
        mvc.perform(get("/v1/admin/actors/" + human.getId())
                        .header("Authorization", "Bearer " + superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.handle").value("contract_human"))
                .andExpect(jsonPath("$.data.agent_profile").value(nullValue()));

        mvc.perform(get("/v1/admin/actors/999999")
                        .header("Authorization", "Bearer " + superToken))
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    @DisplayName("封禁：缺 status 回 40001（一次「没带参数」不能静默解封一个号）")
    void actorStatusRequiresExplicitValue() throws Exception {
        Actor actor = actors.put(7102L, "contract_ban_me");
        mvc.perform(patch("/v1/admin/actors/" + actor.getId() + "/status")
                        .header("Authorization", "Bearer " + superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(jsonPath("$.code").value(40001));
        mvc.perform(patch("/v1/admin/actors/" + actor.getId() + "/status")
                        .header("Authorization", "Bearer " + superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":9}"))
                .andExpect(jsonPath("$.code").value(40002));

        mvc.perform(patch("/v1/admin/actors/" + actor.getId() + "/status")
                        .header("Authorization", "Bearer " + superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":2,\"reason\":\"契约用例\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(2));
        // 审计里要有「谁、对谁、为什么」：reason 进了 detail，而不是被丢掉
        assertThat(store.audits.last().getDetail()).contains("契约用例");
    }

    // ------------------------------------------------------------------ 内容

    @Test
    @DisplayName("删帖：回显 target_type/target_id，reason 走查询参数（DELETE + body 会被代理丢掉）")
    void deletePostPassesReasonAndDelegates() throws Exception {
        Actor author = actors.put(7201L, "contract_author");
        Post post = new Post();
        post.setId(7202L);
        post.setAuthorId(author.getId());
        post.setContent("{\"text\":\"hello\"}");
        post.setVisibility(Visibility.PUBLIC);
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setCreatedAt(LocalDateTime.now());
        posts.insert(post);

        mvc.perform(delete("/v1/admin/posts/" + post.getId())
                        .param("reason", "广告")
                        .header("Authorization", "Bearer " + superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.target_type").value(AdminService.TARGET_POST))
                .andExpect(jsonPath("$.data.target_id").value(post.getId()))
                .andExpect(jsonPath("$.data.deleted").value(true));

        // 删除走的必须是那条级联路径（端口），而不是后台自己再实现一遍
        assertThat(deletedPosts).containsExactly(post.getId());
        assertThat(store.audits.withAction(AdminService.ACTION_POST_DELETE)).hasSize(1);

        mvc.perform(delete("/v1/admin/posts/999999")
                        .header("Authorization", "Bearer " + superToken))
                .andExpect(jsonPath("$.code").value(40404));
    }

    @Test
    @DisplayName("内容列表：作者 ID 是数字、content 原样透出 JSON 文本（审核要看原文）")
    void postListPassesRawContent() throws Exception {
        Actor author = actors.put(7301L, "contract_author2");
        Post post = new Post();
        post.setId(7302L);
        post.setAuthorId(author.getId());
        post.setContent("{\"text\":\"原文不动\"}");
        post.setVisibility(Visibility.PUBLIC);
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setCreatedAt(LocalDateTime.now());
        posts.insert(post);

        mvc.perform(get("/v1/admin/posts?limit=1").header("Authorization", "Bearer " + superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].post_id").value(post.getId()))
                .andExpect(jsonPath("$.data.items[0].author_id").value(author.getId()))
                .andExpect(jsonPath("$.data.items[0].content").value("{\"text\":\"原文不动\"}"));
    }

    // ------------------------------------------------------------------ 审计

    @Test
    @DisplayName("审计查询：四个过滤条件都真的下推到仓储（否则「查了」只是看起来查了）")
    void auditFiltersReachRepository() throws Exception {
        Actor actor = actors.put(7401L, "contract_audited");
        mvc.perform(patch("/v1/admin/actors/" + actor.getId() + "/status")
                        .header("Authorization", "Bearer " + superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":2}"))
                .andExpect(status().isOk());

        AdminUser me = store.users.findByUsername(SUPER_USERNAME).orElseThrow();
        mvc.perform(get("/v1/admin/audit-logs?action=" + AdminService.ACTION_ACTOR_STATUS
                        + "&target_type=" + AdminService.TARGET_ACTOR
                        + "&target_id=" + actor.getId()
                        + "&admin_id=" + me.getId())
                        .header("Authorization", "Bearer " + superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].admin_name").value(SUPER_USERNAME))
                .andExpect(jsonPath("$.data.items[0].target_id").value(actor.getId()));

        // 换一个不相干的 target_id：同一条审计不该被查出来
        mvc.perform(get("/v1/admin/audit-logs?target_id=1")
                        .header("Authorization", "Bearer " + superToken))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    @DisplayName("审计没有删除接口：DELETE /v1/admin/audit-logs 不映射到任何处理（405/404）")
    void auditLogsAreAppendOnly() throws Exception {
        int status = mvc.perform(delete("/v1/admin/audit-logs")
                        .header("Authorization", "Bearer " + superToken))
                .andReturn().getResponse().getStatus();
        assertThat(status).isNotEqualTo(200);
    }
}
