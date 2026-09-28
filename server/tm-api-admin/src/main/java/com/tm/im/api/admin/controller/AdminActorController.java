package com.tm.im.api.admin.controller;

import com.tm.im.api.admin.auth.CurrentAdmin;
import com.tm.im.api.admin.view.ActorAdminView;
import com.tm.im.api.admin.view.ActorPageView;
import com.tm.im.api.admin.view.ActorStatusRequest;
import com.tm.im.api.admin.view.AdminViews;
import com.tm.im.api.admin.web.AdminParams;
import com.tm.im.common.api.ApiResponse;
import com.tm.im.core.admin.AdminContext;
import com.tm.im.core.admin.AdminService;
import com.tm.im.domain.entity.Actor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;

/**
 * 参与者管理（用户 + Agent）—— 后台最常用的那一页。
 *
 * <p><b>没有 {@code /v1/admin/agents} 这一组接口</b>：Agent 与人在库里是同一张
 * 表的同一批行，{@code actor_type=2} 就是 Agent 列表。为它另开一组接口
 * 会把「对等」从后台这一侧破功，而后台恰好是最容易长出「Agent 特例」的地方
 * （产品上总有「Agent 是不是要单独看」的诉求）。详情接口会额外带上
 * {@code agent_profile}，人返回 null——那是<b>展示差异</b>，不是模型差异。
 */
@RestController
@RequestMapping("/v1/admin/actors")
public class AdminActorController {

    private final AdminService admins;
    private final ZoneId databaseZone;

    public AdminActorController(AdminService admins, ZoneId databaseZone) {
        this.admins = admins;
        this.databaseZone = databaseZone;
    }

    /** 列表：可按类型 / 状态 / handle 前缀过滤，按注册时间倒序。 */
    @GetMapping
    public ApiResponse<ActorPageView> list(@CurrentAdmin AdminContext caller,
                                           @RequestParam(required = false) Integer limit,
                                           @RequestParam(required = false) String cursor,
                                           @RequestParam(name = "actor_type", required = false)
                                           Integer actorType,
                                           @RequestParam(required = false) Integer status,
                                           @RequestParam(name = "handle_prefix", required = false)
                                           String handlePrefix) {
        AdminService.Page<Actor> page = admins.listActors(caller,
                limit == null ? 0 : limit, cursor,
                AdminParams.actorType(actorType), AdminParams.actorStatus(status), handlePrefix);
        return ApiResponse.ok(new ActorPageView(
                AdminViews.actors(page.items(), databaseZone), page.nextCursor(), page.hasMore()));
    }

    /** 详情：Actor 本体 + Agent 扩展（人没有后者）。 */
    @GetMapping("/{actorId}")
    public ApiResponse<ActorAdminView> detail(@CurrentAdmin AdminContext caller,
                                              @PathVariable long actorId) {
        // 详情不做权限分级：能进后台就能看。理由是它读的是公开资料
        // （handle/昵称/简介）——真正需要限制的是写动作（见下面那个 PATCH）。
        return ApiResponse.ok(AdminViews.actor(admins.getActor(actorId), databaseZone));
    }

    /**
     * 封禁 / 解封（{@code PATCH /v1/admin/actors/{id}/status}）。
     *
     * <p>这是 M9 验收标准里的「可封禁」。写的是 {@code actor.status}——
     * 与用户端鉴权读的是同一列，所以封禁立刻生效，不需要任何同步机制。
     */
    @PatchMapping("/{actorId}/status")
    public ApiResponse<ActorAdminView> updateStatus(@CurrentAdmin AdminContext caller,
                                                    @PathVariable long actorId,
                                                    @RequestBody(required = false)
                                                    ActorStatusRequest body) {
        Actor actor = admins.setActorStatus(caller, actorId,
                AdminParams.requiredActorStatus(body == null ? null : body.status()),
                body == null ? null : body.reason());
        return ApiResponse.ok(AdminViews.actor(actor, databaseZone));
    }
}
