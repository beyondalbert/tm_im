package com.tm.im.api.admin.controller;

import com.tm.im.api.admin.auth.CurrentAdmin;
import com.tm.im.api.admin.view.AdminCreateRequest;
import com.tm.im.api.admin.view.AdminPageView;
import com.tm.im.api.admin.view.AdminStatusRequest;
import com.tm.im.api.admin.view.AdminView;
import com.tm.im.api.admin.view.AdminViews;
import com.tm.im.api.admin.web.AdminParams;
import com.tm.im.common.api.ApiResponse;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.core.admin.AdminContext;
import com.tm.im.core.admin.AdminService;
import com.tm.im.domain.entity.AdminUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;

/**
 * 后台账号管理。<b>整组都只有 SUPER 能调</b>（判据在 {@code AdminService} 里，
 * 而不在这里——权限判断写在控制器里，就会随接口数量增长而出现漏写的那一个）。
 *
 * <p>为什么要有这一组：{@code /v1/admin} 的能力是「封任何人的号」，
 * 所以「谁能进后台」这件事本身必须有界面可管。否则唯一的做法是直接改库，
 * 而改库的后果（忘记给 rol e、口令哈希格式拼错）都只在登录时才发现。
 */
@RestController
@RequestMapping("/v1/admin/accounts")
public class AdminAccountController {

    private final AdminService admins;
    private final ZoneId databaseZone;

    public AdminAccountController(AdminService admins, ZoneId databaseZone) {
        this.admins = admins;
        this.databaseZone = databaseZone;
    }

    @GetMapping
    public ApiResponse<AdminPageView> list(@CurrentAdmin AdminContext caller,
                                          @RequestParam(required = false) Integer limit,
                                          @RequestParam(required = false) String cursor) {
        AdminService.Page<AdminUser> page = admins.listAdmins(caller, limit == null ? 0 : limit, cursor);
        return ApiResponse.ok(new AdminPageView(
                AdminViews.admins(page.items(), databaseZone), page.nextCursor(), page.hasMore()));
    }

    @GetMapping("/{adminId}")
    public ApiResponse<AdminView> detail(@CurrentAdmin AdminContext caller,
                                        @PathVariable long adminId) {
        return ApiResponse.ok(AdminViews.admin(admins.getAdmin(caller, adminId), databaseZone));
    }

    /** 新建账号。明文口令只进不回——响应里没有任何口令字段（见 AdminView）。 */
    @PostMapping
    public ApiResponse<AdminView> create(@CurrentAdmin AdminContext caller,
                                        @RequestBody(required = false) AdminCreateRequest body) {
        if (body == null) {
            throw new TmException(ErrorCode.BAD_REQUEST, "缺少请求体");
        }
        AdminUser created = admins.createAdmin(caller, body.username(), body.password(),
                body.displayName(), AdminParams.adminRole(body.role()));
        return ApiResponse.ok(AdminViews.admin(created, databaseZone));
    }

    /**
     * 停用 / 启用。
     *
     * <p>停用会连带踢掉目标账号的全部会话（在同一个事务里），所以「停用」
     * 的语义是「立刻没有权限」，而不是「下次登录时会被拒」。
     */
    @PatchMapping("/{adminId}/status")
    public ApiResponse<AdminView> updateStatus(@CurrentAdmin AdminContext caller,
                                               @PathVariable long adminId,
                                               @RequestBody(required = false)
                                               AdminStatusRequest body) {
        admins.setAdminStatus(caller, adminId,
                AdminParams.adminStatus(body == null ? null : body.status()));
        return ApiResponse.ok(AdminViews.admin(admins.getAdmin(caller, adminId), databaseZone));
    }
}
