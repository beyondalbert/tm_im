package com.tm.im.api.admin.controller;

import com.tm.im.api.admin.auth.ClientIps;
import com.tm.im.api.common.auth.BearerCredential;
import com.tm.im.api.admin.auth.CurrentAdmin;
import com.tm.im.api.admin.view.AdminLoginRequest;
import com.tm.im.api.admin.view.AdminLoginView;
import com.tm.im.api.admin.view.AdminView;
import com.tm.im.api.admin.view.AdminViews;
import com.tm.im.common.api.ApiResponse;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.core.admin.AdminContext;
import com.tm.im.core.admin.AdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;

/**
 * 后台认证：登录 / 登出 / 当前身份。
 *
 * <p>与用户端的 {@code /v1/auth/*} 是两套：用户端发的是 JWT（无状态、2 小时），
 * 后台发的是库里的会话（可即时吊销、8 小时）。这不是「重复实现」，
 * 而是两个不同的威胁模型：用户账号有几百万个，每个请求都要鉴权，
 * 状态化的代价是每请求一次查表；后台账号只有几个，
 * 而它需要的是「停用立刻生效」。
 */
@RestController
@RequestMapping("/v1/admin")
public class AdminAuthController {

    private final AdminService admins;
    private final ZoneId databaseZone;

    public AdminAuthController(AdminService admins, ZoneId databaseZone) {
        this.admins = admins;
        this.databaseZone = databaseZone;
    }

    /** 建立后台会话。失败一律 40101（不区分用户名不存在与口令不对，见 AdminService#login）。 */
    @PostMapping("/auth/login")
    public ApiResponse<AdminLoginView> login(@RequestBody(required = false) AdminLoginRequest body,
                                             HttpServletRequest request) {
        if (body == null || body.username() == null || body.password() == null) {
            throw new TmException(ErrorCode.MISSING_PARAMETER, "缺少 username 或 password");
        }
        AdminService.LoginResult result = admins.login(body.username(), body.password(),
                ClientIps.of(request), request.getHeader("User-Agent"));
        return ApiResponse.ok(new AdminLoginView(result.token(),
                AdminViews.instant(result.expiresAt(), databaseZone),
                AdminViews.admin(result.admin(), databaseZone)));
    }

    /**
     * 登出。幂等：凭证已失效也回成功。
     *
     * <p>登出<b>不做鉴权</b>（不要求 {@code @CurrentAdmin}）：一个已经过期的会话
     * 也应该能「登出成功」——否则客户端会陷入「登出失败 → 不敢清本地状态 →
     * 拿着过期凭证继续请求」的循环。这里只需要凭证本身能把那一行删掉，
     * 而 {@code logout} 的入参就是凭证。
     */
    @PostMapping("/auth/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        admins.logout(BearerCredential.optional(request.getHeader(BearerCredential.HEADER)));
        return ApiResponse.ok(null);
    }

    /**
     * 当前身份（含角色）—— 前端用它决定要不要渲染「后台账号管理」那一页。
     *
     * <p>刻意不用 {@code getAdmin(...)}（那个是 SUPER only）：OPS 也必须能读到
     * 「我是谁、我是什么角色」，否则前端无法隐藏它没有权限的入口。
     * 这条边界曾经写错，被 {@code AdminHttpIT#opsCannotManageAccounts} 抓到（403 而不是 200）。
     */
    @GetMapping("/me")
    public ApiResponse<AdminView> me(@CurrentAdmin AdminContext caller) {
        return ApiResponse.ok(AdminViews.admin(admins.currentAdmin(caller), databaseZone));
    }
}
