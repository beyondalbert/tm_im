package com.tm.im.api.user.controller;

import com.tm.im.api.user.auth.CurrentActor;
import com.tm.im.api.user.view.AgentCreateRequest;
import com.tm.im.api.user.view.AgentCreatedView;
import com.tm.im.api.user.view.AgentPatchRequest;
import com.tm.im.api.user.view.AgentRotatedKeyView;
import com.tm.im.api.user.view.AgentView;
import com.tm.im.api.user.view.AgentViews;
import com.tm.im.common.api.ApiResponse;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.core.agent.AgentService;
import com.tm.im.core.identity.AuthContext;
import com.tm.im.domain.enums.PushMode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.util.List;

/**
 * Agent 管理（03-rest-api.md §7 / 02-auth.md §3）。
 *
 * <p>这一组接口只能由 Agent 的<b>拥有者</b>调用（否则 40302），而调用者用
 * 人类 JWT 或 api_key 都一样 —— 「对等」在这里表现为：平台不区分「谁来创建」，
 * 只区分「你是不是它的拥有者」。
 *
 * <p>控制器里没有任何业务判断：handle 规则、push_mode/endpoint 的组合校验、
 * 停用语义（保留 api_key 哈希以便回 40301 而不是 40105）全在 {@link AgentService}。
 */
@RestController
@RequestMapping("/v1/agents")
public class AgentController {

    private final AgentService agents;
    private final ZoneId databaseZone;

    public AgentController(AgentService agents, ZoneId databaseZone) {
        this.agents = agents;
        this.databaseZone = databaseZone;
    }

    /** §3.1 创建 Agent（响应里带 api_key 与 webhook_secret，仅此一次）。 */
    @PostMapping
    public ApiResponse<AgentCreatedView> create(@CurrentActor AuthContext caller,
                                                @RequestBody AgentCreateRequest request) {
        AgentService.Created created = agents.create(caller.actorId(), new AgentService.CreateCommand(
                request.handle(),
                request.displayName(),
                request.bio(),
                pushMode(request.pushMode(), true),
                request.endpointUrl(),
                request.capabilities(),
                request.modelInfo(),
                request.rateLimit()));
        return ApiResponse.ok(AgentViews.created(created, databaseZone));
    }

    /** §7 我创建的 Agent 列表。 */
    @GetMapping
    public ApiResponse<List<AgentView>> list(@CurrentActor AuthContext caller) {
        return ApiResponse.ok(AgentViews.list(agents.list(caller.actorId()), databaseZone));
    }

    /** §7 Agent 详情（只有拥有者能看：里面有 webhook 地址与配额）。 */
    @GetMapping("/{actorId}")
    public ApiResponse<AgentView> detail(@CurrentActor AuthContext caller,
                                        @PathVariable long actorId) {
        return ApiResponse.ok(AgentViews.view(agents.detail(caller.actorId(), actorId), databaseZone));
    }

    /** §3.3 修改配置。{@code null} 表示「这一项不改」。 */
    @PatchMapping("/{actorId}")
    public ApiResponse<AgentView> update(@CurrentActor AuthContext caller,
                                        @PathVariable long actorId,
                                        @RequestBody AgentPatchRequest request) {
        AgentService.Patch patch = new AgentService.Patch(
                request.displayName(),
                request.bio(),
                pushMode(request.pushMode(), false),
                request.endpointUrl(),
                request.capabilities(),
                request.modelInfo(),
                request.rateLimit());
        return ApiResponse.ok(AgentViews.view(agents.update(caller.actorId(), actorId, patch),
                databaseZone));
    }

    /**
     * §3.2 轮换 api_key：旧的立即失效。
     *
     * <p>它是这一个资源上<b>唯一</b>会再回一次明文凭据的接口，所以客户端的
     * 处理必须是「原子替换」（先落盘新的、再让 Agent 用它）——
     * 中间夹一次旧 key 的调用会得到 {@code 40105}。
     */
    @PostMapping("/{actorId}/rotate-key")
    public ApiResponse<AgentRotatedKeyView> rotateKey(@CurrentActor AuthContext caller,
                                                      @PathVariable long actorId) {
        return ApiResponse.ok(AgentViews.rotateKey(actorId,
                agents.rotateKey(caller.actorId(), actorId)));
    }

    /**
     * §3.4 停用 Agent。
     *
     * <p>语义是「{@code status=2}，其所有凭证失效」：后续用它的 api_key 调用会得到
     * {@code 40301}（账号被停用）而不是 {@code 40105}——那个码的客户端动作是
     * 「停止重试、告诉用户」，而 Agent 自己无权轮换密钥，回 40105 会让它一直重试。
     */
    @DeleteMapping("/{actorId}")
    public ApiResponse<AgentView> disable(@CurrentActor AuthContext caller,
                                          @PathVariable long actorId) {
        return ApiResponse.ok(AgentViews.view(agents.disable(caller.actorId(), actorId),
                databaseZone));
    }

    /**
     * {@code push_mode} 的数字 → 枚举。
     *
     * <p>三个码分别对应三件事：{@code null}（字段缺失，40001）、
     * 不在 1/2/3 里（取值非法，40002）、其它（绑定失败 40000）。
     * 「缺失」与「非法」必须分开：客户端的动作不同（补字段 vs 改取值）。
     *
     * @param required 创建时必填（没有默认模式：默认成 WEBHOOK 会给一个没有地址的
     *                 Agent 一个永远投不出去的推送模式，而它要等到第一条消息才发现）
     */
    private static PushMode pushMode(Integer raw, boolean required) {
        if (raw == null) {
            if (required) {
                throw new TmException(ErrorCode.MISSING_PARAMETER,
                        "push_mode 缺失（1=WEBHOOK 2=WS 3=PULL）");
            }
            return null;
        }
        PushMode mode = PushMode.of(raw);
        if (mode == null) {
            throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "push_mode=" + raw + "（可选 1=WEBHOOK 2=WS 3=PULL）");
        }
        return mode;
    }
}
