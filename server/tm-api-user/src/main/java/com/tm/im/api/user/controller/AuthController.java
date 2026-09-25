package com.tm.im.api.user.controller;

import com.tm.im.api.user.auth.CurrentActor;
import com.tm.im.api.user.auth.DeviceId;
import com.tm.im.api.user.view.LoginRequest;
import com.tm.im.api.user.view.RefreshRequest;
import com.tm.im.api.user.view.RegisterRequest;
import com.tm.im.api.user.view.TokenView;
import com.tm.im.common.api.ApiResponse;
import com.tm.im.core.identity.AccountService;
import com.tm.im.core.identity.AuthContext;
import com.tm.im.domain.repository.RefreshSession;
import com.tm.im.domain.repository.RefreshTokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * 人类账号的登录与令牌（02-auth.md §2）。
 *
 * <p>本控制器<b>没有任何</b>参数校验代码：规则全在 {@link AccountService} 里，
 * 失败一律抛 {@code TmException}，由 {@code ApiExceptionHandler} 翻译成
 * 统一信封。控制器只做「请求体 → 服务入参」「服务出参 → 视图」两件事，
 * 因为第三件事（判断）一旦开始写，就会有第二处与它不一致。
 */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AccountService accounts;
    private final RefreshTokenStore refreshTokens;

    public AuthController(AccountService accounts, RefreshTokenStore refreshTokens) {
        this.accounts = accounts;
        this.refreshTokens = refreshTokens;
    }

    @PostMapping("/register")
    public ApiResponse<TokenView> register(
            @RequestBody RegisterRequest request,
            @RequestHeader(value = DeviceId.HEADER, required = false) String deviceId) {
        AccountService.TokenPair pair = accounts.register(new AccountService.RegisterCommand(
                request.handle(), request.password(), request.displayName(),
                DeviceId.from(deviceId)));
        return ApiResponse.ok(TokenView.of(pair));
    }

    @PostMapping("/login")
    public ApiResponse<TokenView> login(
            @RequestBody LoginRequest request,
            @RequestHeader(value = DeviceId.HEADER, required = false) String deviceId) {
        AccountService.TokenPair pair = accounts.login(new AccountService.LoginCommand(
                request.handle(), request.password(), DeviceId.from(deviceId)));
        return ApiResponse.ok(TokenView.of(pair));
    }

    /**
     * 用 refresh_token 换一对新令牌。
     *
     * <p>这个接口<b>不带</b> Authorization 头：调用它的那一刻，access token
     * 往往刚刚过期（这是它被调用的最常见原因），要求带上等于要求客户端
     * 先做一件做不到的事。
     */
    @PostMapping("/refresh")
    public ApiResponse<TokenView> refresh(@RequestBody RefreshRequest request) {
        return ApiResponse.ok(TokenView.of(accounts.refresh(request.refreshToken())));
    }

    /**
     * 登出（02-auth.md §2.4）。
     *
     * <p><b>与文档的一处差异</b>：§2.4 只画了 {@code Authorization} 头，
     * 但登出要真正生效，必须让服务端作废 refresh_token——否则客户端丢掉了
     * 本地副本，服务端那条会话却还在等下一个持有者。所以这里额外接受一个
     * <b>可选</b>的 {@code refresh_token} 字段（文档已同步）。
     *
     * <p>为什么要带 Authorization 头（哪怕它对登出不是必需的）：
     * 没有它，这就成了一个「任何人拿一个字符串就能反复调」的接口；
     * 有了它，作废别人凭证的前提是 <b>你自己已经登录</b>，
     * 而下面还有一条「凭证必须属于调用者」的检查。
     *
     * <p>access token 本身无法作废（它是无状态 JWT，2 小时后自然过期）。
     * 这一点必须说清楚，否则会以为「登出之后我的 token 立刻失效了」。
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @CurrentActor AuthContext caller,
            @RequestBody(required = false) RefreshRequest request) {
        String refreshToken = request == null ? null : request.refreshToken();
        if (refreshToken == null || refreshToken.isBlank()) {
            // 客户端只带 access token 也算登出成功：它至少已经丢掉了本地副本。
            // 回错误会让「登出」这个动作在某些客户端上永远失败。
            log.debug("登出未带 refresh_token actorId={}", caller.actorId());
            return ApiResponse.ok();
        }

        Optional<RefreshSession> session = refreshTokens.peek(refreshToken);
        if (session.isEmpty()) {
            // 已用过 / 已过期：登出是幂等的（客户端弱网重试是常态）
            return ApiResponse.ok();
        }
        if (session.get().actorId() != caller.actorId()) {
            // 别人的凭证。不回 403：那等于确认「这个凭证存在，只是不属于你」，
            // 而凭证的存在性正是攻击者想探测的信息。记 WARN 让真正的
            // 「有人拿着别人的 refresh_token 在试探」留下痕迹。
            log.warn("登出时 refresh_token 不属于调用者 caller={} owner={}",
                    caller.actorId(), session.get().actorId());
            return ApiResponse.ok();
        }
        accounts.logout(refreshToken);
        log.debug("登出成功 actorId={}", caller.actorId());
        return ApiResponse.ok();
    }
}
