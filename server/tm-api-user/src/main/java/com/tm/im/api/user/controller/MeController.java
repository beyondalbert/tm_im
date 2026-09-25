package com.tm.im.api.user.controller;

import com.tm.im.api.user.auth.CurrentActor;
import com.tm.im.api.user.view.ActorView;
import com.tm.im.api.user.view.ActorViews;
import com.tm.im.common.api.ApiResponse;
import com.tm.im.core.identity.AuthContext;
import com.tm.im.core.identity.IdentityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;

/**
 * 当前身份（03-rest-api.md §2.1）。
 *
 * <p>路径是 {@code /v1/me} 而不是 {@code /v1/actors/me}：文档里就是这样，
 * 而 {@code /v1/actors/{actor_id}} 这个路由是存在的（§2.2），
 * 两者叠在一起会让 {@code me} 变成一个「指某个 ID 为 me 的人」的歧义路径。
 */
@RestController
public class MeController {

    private final IdentityService identity;
    private final ZoneId databaseZone;

    public MeController(IdentityService identity, ZoneId databaseZone) {
        this.identity = identity;
        this.databaseZone = databaseZone;
    }

    /**
     * <p>这里<b>又查了一次</b>库（参数解析时已经查过一次）：两次查库看起来是浪费，
     * 但它们的语义不同——解析器那次是「鉴权」（凭证有效吗、账号还在吗），
     * 这次是「取资料」。若让解析器把 Actor 缓存进 {@code AuthContext}，
     * 长连接的寿命是小时级，那样会把「资料改了但连接还在用旧值」变成一个
     * 需要额外的失效通知才能解决的问题。REST 请求是短生命周期的，
     * 这一次点查换来的是一致性。
     */
    @GetMapping("/v1/me")
    public ApiResponse<ActorView> me(@CurrentActor AuthContext caller) {
        return ApiResponse.ok(ActorViews.toView(identity.requireActive(caller.actorId()), databaseZone));
    }
}
