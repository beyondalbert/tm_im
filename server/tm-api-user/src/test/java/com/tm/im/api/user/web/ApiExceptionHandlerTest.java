package com.tm.im.api.user.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tm.im.api.common.web.ApiExceptionHandler;
import com.tm.im.api.user.auth.CurrentActor;
import com.tm.im.api.user.auth.CurrentActorArgumentResolver;
import com.tm.im.common.api.ApiResponse;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.core.identity.AuthContext;
import com.tm.im.core.identity.IdentityService;
import com.tm.im.core.identity.JwtTokenService;
import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.enums.ActorStatus;
import com.tm.im.domain.enums.ActorType;
import com.tm.im.domain.repository.ActorRepository;
import com.tm.im.domain.repository.ActorSecretRepository;
import com.tm.im.domain.enums.SecretType;
import com.tm.im.domain.entity.ActorSecret;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统一异常翻译的逐条契约（03-rest-api.md §1.3 / §1.4）。
 *
 * <p><b>为什么值得单独一个测试类</b>：错误码与 HTTP 状态码是客户端唯一的依据，
 * 而任何一个映射写错，功能测试都是绿的——它们只跑成功路径。
 * 这里的控制器是<b>故意写坏的</b>：被测对象是「异常 → 响应」这一段，不是业务。
 */
class ApiExceptionHandlerTest {

    private static final String SECRET_LOOKING_DETAIL = "SELECT * FROM actor WHERE id=1";
    private static final String JWT_SECRET = "0123456789abcdef0123456789abcdef";
    private static final long ACTOR_ID = 4242L;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // 解析器必须装上：不然 Spring 会把 AuthContext 参数当成一个要数据绑定的
        // 模型属性去尝试构造，报出来的是一句与鉴权毫不相干的异常。
        IdentityService identity = new IdentityService(new SingleActorRepository(),
                new NoSecrets(), new JwtTokenService(JWT_SECRET));
        mvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new CurrentActorArgumentResolver(identity))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(productionLikeMapper()))
                .build();
    }

    /**
     * 与生产配置<b>同源不同物</b>的 ObjectMapper。
     *
     * <p>生产那份由 Spring Boot 按 {@code application.yml} 装配（{@code spring.jackson.*}），
     * 这里只能就地构造：{@code Json.mapper()} 不能用——它的 {@code NON_NULL}
     * 是给消息 content 用的（省字节），会让 {@code data: null} 整个消失，
     * 于是本测试就再也验证不了「失败时 data 是 null 而不是缺字段」这条契约
     * （03-rest-api.md §1.3 的示例里明明白白写着 {@code "data": null}）。
     *
     * <p>「配置文件里的两条设置真的生效了吗」不能由本类回答，它由 {@code tm-app}
     * 的启动集成测试用真实 HTTP 钉住。
     */
    static ObjectMapper productionLikeMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .setSerializationInclusion(JsonInclude.Include.ALWAYS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    @DisplayName("业务失败：HTTP 200 + 表格里的 code/message，且 data 显式为 null")
    void businessFailureIsHttp200() throws Exception {
        mvc.perform(get("/boom/handle-exists"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(40005))
                .andExpect(jsonPath("$.message").value("handle exists"))
                .andExpect(jsonPath("$.data").value(Matchers.nullValue()));
    }

    @Test
    @DisplayName("认证失败：HTTP 401（客户端据此决定刷新还是重登）")
    void unauthorizedIsHttp401() throws Exception {
        mvc.perform(get("/boom/unauthorized"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
        mvc.perform(get("/boom/expired"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40103));
    }

    @Test
    @DisplayName("账号被停用：HTTP 403（与 401 的区别决定客户端是否停止重试）")
    void suspendedIsHttp403() throws Exception {
        mvc.perform(get("/boom/suspended"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("服务过载：HTTP 503（文档 §1.4 的唯一例外）")
    void overloadedIsHttp503() throws Exception {
        mvc.perform(get("/boom/overloaded"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(50004));
    }

    @Test
    @DisplayName("内部 detail 绝不进响应体")
    void detailNeverLeaks() throws Exception {
        mvc.perform(get("/boom/unauthorized"))
                .andExpect(jsonPath("$.message").value("unauthorized"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("handle=alice"))));
    }

    @Test
    @DisplayName("未捕获异常：HTTP 500 + 50000，且不泄漏异常消息")
    void unexpectedExceptionIsOpaque() throws Exception {
        mvc.perform(get("/boom/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50000))
                .andExpect(jsonPath("$.message").value("internal error"))
                // 异常消息里常有 SQL 片段、表名、参数值：
                // 日志里有价值，响应体里是信息泄漏
                .andExpect(content().string(Matchers.not(
                        Matchers.containsString(SECRET_LOOKING_DETAIL))));
    }

    @Test
    @DisplayName("请求体不是合法 JSON：HTTP 200 + 40000（业务失败，不是没到应用）")
    void malformedJsonIsBusinessFailure() throws Exception {
        mvc.perform(post("/boom/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"handle\": "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("缺少必填参数：HTTP 200 + 40001")
    void missingParameterIsHttp200() throws Exception {
        mvc.perform(get("/boom/echo-required"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("参数类型不符：HTTP 200 + 40002")
    void typeMismatchIsHttp200() throws Exception {
        mvc.perform(get("/boom/number").param("value", "not-a-number"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40002));
    }

    @Test
    @DisplayName("缺少 Authorization 头：HTTP 401 + 40101（走解析器，不是控制器）")
    void missingAuthorizationHeaderIsHttp401() throws Exception {
        mvc.perform(get("/boom/authenticated"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("Authorization 格式错：HTTP 401 + 40102")
    void malformedAuthorizationIsHttp401() throws Exception {
        mvc.perform(get("/boom/authenticated").header("Authorization", "Basic Zm9v"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40102));
    }

    @Test
    @DisplayName("有效凭证：解析器真的跑了（拿到的 actorId 只能来自解析结果）")
    void validCredentialReachesController() throws Exception {
        JwtTokenService tokens = new JwtTokenService(JWT_SECRET);
        String token = tokens.issue(ACTOR_ID, "alice", ActorType.HUMAN, Duration.ofHours(1));
        IdentityService identity = new IdentityService(new SingleActorRepository(),
                new NoSecrets(), tokens);
        MockMvc withAuth = MockMvcBuilders.standaloneSetup(new WhoAmIController())
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new CurrentActorArgumentResolver(identity))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(productionLikeMapper()))
                .build();

        withAuth.perform(get("/whoami").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(4242));
    }

    @Test
    @DisplayName("过期 token：HTTP 401 + 40103（retryable，客户端应刷新后重试）")
    void expiredTokenIsHttp401() throws Exception {
        // 用一个「签发的瞬间就已经过期」的时钟，而不是 sleep：
        // 时间相关的用例必须确定性地命中，否则它只在偶尔的机器负载下失败
        JwtTokenService expired = new JwtTokenService(JWT_SECRET,
                java.time.Clock.fixed(java.time.Instant.now().minusSeconds(7200),
                        java.time.ZoneOffset.UTC));
        String token = expired.issue(ACTOR_ID, "alice", ActorType.HUMAN, Duration.ofMinutes(1));
        IdentityService identity = new IdentityService(new SingleActorRepository(),
                new NoSecrets(), new JwtTokenService(JWT_SECRET));
        MockMvc withAuth = MockMvcBuilders.standaloneSetup(new WhoAmIController())
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new CurrentActorArgumentResolver(identity))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(productionLikeMapper()))
                .build();

        withAuth.perform(get("/whoami").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40103));
    }

    // ------------------------------------------------------------------
    // 测试用的控制器与替身：只负责按需失败
    // ------------------------------------------------------------------

    @RestController
    static class FailingController {

        @GetMapping("/boom/handle-exists")
        void handleExists() {
            throw new TmException(ErrorCode.HANDLE_EXISTS, "handle=alice");
        }

        @GetMapping("/boom/unauthorized")
        void unauthorized() {
            throw new TmException(ErrorCode.UNAUTHORIZED, "handle=alice");
        }

        @GetMapping("/boom/expired")
        void expired() {
            throw new TmException(ErrorCode.TOKEN_EXPIRED);
        }

        @GetMapping("/boom/suspended")
        void suspended() {
            throw new TmException(ErrorCode.ACCOUNT_SUSPENDED);
        }

        @GetMapping("/boom/overloaded")
        void overloaded() {
            throw new TmException(ErrorCode.SERVICE_OVERLOADED);
        }

        @GetMapping("/boom/unexpected")
        void unexpected() {
            throw new IllegalStateException("boom: " + SECRET_LOOKING_DETAIL);
        }

        @PostMapping("/boom/echo")
        ApiResponse<Payload> echo(@RequestBody Payload body) {
            return ApiResponse.ok(body);
        }

        @GetMapping("/boom/echo-required")
        ApiResponse<String> echoRequired(@RequestParam String required) {
            return ApiResponse.ok(required);
        }

        @GetMapping("/boom/number")
        ApiResponse<Integer> number(@RequestParam int value) {
            return ApiResponse.ok(value);
        }

        @GetMapping("/boom/authenticated")
        ApiResponse<Long> authenticated(@CurrentActor AuthContext caller) {
            return ApiResponse.ok(caller.actorId());
        }
    }

    /** 请求体用一个具名类型：用 String 当 {@code @RequestBody} 会走另一套转换器，验不到 JSON 解析失败。 */
    record Payload(String handle) {
    }

    @RestController
    private static final class WhoAmIController {
        @GetMapping("/whoami")
        ApiResponse<Long> whoami(@CurrentActor AuthContext caller) {
            return ApiResponse.ok(caller.actorId());
        }
    }

    /** 只有一个 actor 的仓储替身（本类只需要「查得到」这一条路径）。 */
    private static final class SingleActorRepository implements ActorRepository {

        @Override
        public Optional<Actor> findById(long id) {
            if (id != ACTOR_ID) {
                return Optional.empty();
            }
            Actor actor = new Actor();
            actor.setId(ACTOR_ID);
            actor.setHandle("alice");
            actor.setActorType(ActorType.HUMAN);
            actor.setStatus(ActorStatus.ACTIVE);
            return Optional.of(actor);
        }

        @Override
        public Optional<Actor> findByHandle(String handle) {
            return Optional.empty();
        }

        @Override
        public boolean existsHandle(String handle) {
            return false;
        }

        @Override
        public Actor insert(Actor actor) {
            throw new UnsupportedOperationException("本测试不写库");
        }

        @Override
        public List<Actor> findByIds(List<Long> actorIds) {
            return List.of();
        }

        @Override
        public void update(Actor actor) {
            throw new UnsupportedOperationException("本测试不写库");
        }

        @Override
        public List<Actor> pageForAdmin(Long beforeId, int limit, ActorType actorType,
                                        ActorStatus status, String handlePrefix) {
            return List.of();
        }
    }

    /** 本类只走 JWT 路径，凭据表不需要有内容。 */
    private static final class NoSecrets implements ActorSecretRepository {

        @Override
        public Optional<ActorSecret> find(long actorId, SecretType secretType) {
            return Optional.empty();
        }

        @Override
        public Optional<Long> findActorIdByHash(SecretType secretType, String secretHash) {
            return Optional.empty();
        }

        @Override
        public void upsert(ActorSecret secret) {
            throw new UnsupportedOperationException("本测试不写库");
        }

        @Override
        public boolean delete(long actorId, SecretType secretType) {
            return false;
        }
    }
}
