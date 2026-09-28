package com.tm.im.api.admin.controller;

import com.tm.im.api.admin.auth.CurrentAdmin;
import com.tm.im.api.admin.view.AdminViews;
import com.tm.im.api.admin.view.AuditLogPageView;
import com.tm.im.common.api.ApiResponse;
import com.tm.im.core.admin.AdminContext;
import com.tm.im.core.admin.AdminService;
import com.tm.im.domain.entity.AdminAuditLog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;

/**
 * 审计查询 —— M9 验收标准里的「可查日志」。
 *
 * <p>四个过滤条件对应四种真实问题：某个管理员干过什么、这个对象被谁动过、
 * 这类操作发生过几次、最近都发生了什么（都不传）。
 *
 * <p><b>没有「删除日志」接口</b>：能被改动或删掉的审计只能证明「当时大概是这么回事」。
 * 清理属于 DBA 的归档动作，而不是一个 HTTP 接口。
 */
@RestController
@RequestMapping("/v1/admin/audit-logs")
public class AdminAuditController {

    private final AdminService admins;
    private final ZoneId databaseZone;

    public AdminAuditController(AdminService admins, ZoneId databaseZone) {
        this.admins = admins;
        this.databaseZone = databaseZone;
    }

    @GetMapping
    public ApiResponse<AuditLogPageView> list(
            @CurrentAdmin AdminContext caller,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(name = "admin_id", required = false) Long adminId,
            @RequestParam(required = false) String action,
            @RequestParam(name = "target_type", required = false) String targetType,
            @RequestParam(name = "target_id", required = false) Long targetId) {
        AdminService.Page<AdminAuditLog> page = admins.listAuditLogs(caller,
                limit == null ? 0 : limit, cursor, adminId, action, targetType, targetId);
        return ApiResponse.ok(new AuditLogPageView(
                AdminViews.audits(page.items(), databaseZone), page.nextCursor(), page.hasMore()));
    }
}
