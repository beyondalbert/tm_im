package com.tm.im.api.user.controller;

import com.tm.im.api.user.auth.CurrentActor;
import com.tm.im.api.user.view.FriendBlockView;
import com.tm.im.api.user.view.FriendDecisionView;
import com.tm.im.api.user.view.FriendRemovedView;
import com.tm.im.api.user.view.FriendRequestRequest;
import com.tm.im.api.user.view.FriendRequestView;
import com.tm.im.api.user.view.FriendView;
import com.tm.im.api.user.view.FriendViews;
import com.tm.im.api.user.view.SocialPageView;
import com.tm.im.common.api.ApiResponse;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.core.friend.FriendService;
import com.tm.im.core.identity.AuthContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.util.Locale;

/**
 * 好友（03-rest-api.md §3.1–§3.6）。
 *
 * <p><b>本控制器里没有任何业务判断</b>：谁能加谁、什么时候算过期、
 * 什么状态下是 40901/40902/40903，全在 {@link FriendService}。
 * 这里只做三件机械的事——把路径/查询参数/请求体翻译成服务入参、
 * 把服务出参翻译成视图、把<b>协议层的写法</b>（{@code direction=pending} 这类字符串）
 * 转成领域枚举。
 *
 * <p>那两个字符串参数（{@code direction} / {@code status}）刻意<b>不</b>用枚举绑定：
 * Spring 绑定失败会抛 {@code MethodArgumentTypeMismatchException}，
 * 而它被统一处理器翻成 40002（参数值非法）——这个结果是对的，但错误信息里不会有
 * 「可选值是 incoming/outgoing」这句话。这里显式解析并在 detail 里列出可选值，
 * 让客户端不必去翻文档。
 */
@RestController
@RequestMapping("/v1/friends")
public class FriendController {

    private final FriendService friends;
    private final ZoneId databaseZone;

    public FriendController(FriendService friends, ZoneId databaseZone) {
        this.friends = friends;
        this.databaseZone = databaseZone;
    }

    // ================================================================== 请求

    /** §3.1 发送好友请求。不受「非好友不能发消息」限制（这是建立关系的唯一入口）。 */
    @PostMapping("/requests")
    public ApiResponse<FriendRequestView> request(@CurrentActor AuthContext caller,
                                                  @RequestBody FriendRequestRequest body) {
        return ApiResponse.ok(FriendViews.request(
                friends.request(caller.actorId(), body.targetRef(), body.message()), databaseZone));
    }

    /**
     * §3.2 同意。幂等，且返回自动建好的单聊会话（客户端拿到就能直接聊）。
     *
     * <p>{@code requestId} 用 {@code long} 绑定：非数字路径段会被 Spring 拒掉并回 40002，
     * 这正是我们要的（一个拼错的 id 不该被当成「请求不存在」）。
     */
    @PostMapping("/requests/{requestId}/accept")
    public ApiResponse<FriendDecisionView> accept(@CurrentActor AuthContext caller,
                                                  @PathVariable long requestId) {
        FriendService.AcceptOutcome outcome = friends.accept(caller.actorId(), requestId);
        return ApiResponse.ok(FriendViews.decision(
                outcome.friendship().getRequestId(),
                outcome.friendship().getActorA(),
                outcome.friendship().getActorB(),
                outcome.friendship().getStatus().code(),
                outcome.friendship().getUpdatedAt(),
                outcome.convId(),
                databaseZone));
    }

    /** §3.2 拒绝：删掉整行，响应里 {@code status=0}（「这段关系已不存在」）。 */
    @PostMapping("/requests/{requestId}/reject")
    public ApiResponse<FriendDecisionView> reject(@CurrentActor AuthContext caller,
                                                  @PathVariable long requestId) {
        FriendService.RejectOutcome outcome = friends.reject(caller.actorId(), requestId);
        return ApiResponse.ok(FriendViews.decision(
                outcome.friendship().getRequestId(),
                outcome.friendship().getActorA(),
                outcome.friendship().getActorB(),
                0,
                outcome.friendship().getUpdatedAt(),
                null,
                databaseZone));
    }

    /** §3.3 请求列表。{@code direction}: incoming|outgoing；{@code status}: pending|all。 */
    @GetMapping("/requests")
    public ApiResponse<SocialPageView<com.tm.im.api.user.view.FriendRequestItemView>> listRequests(
            @CurrentActor AuthContext caller,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        boolean incoming = incomingOnly(direction);
        boolean pendingOnly = pendingOnly(status);
        FriendService.Page<FriendService.RelationEntry> page =
                friends.listRequests(caller.actorId(), incoming, pendingOnly, intOrZero(limit), cursor);
        return ApiResponse.ok(new SocialPageView<>(
                FriendViews.requests(page.items(), databaseZone),
                page.nextCursor(),
                page.hasMore()));
    }

    // ================================================================== 列表 / 删除 / 拉黑

    /** §3.4 好友列表（按成为好友的时间倒序）。 */
    @GetMapping
    public ApiResponse<SocialPageView<FriendView>> list(
            @CurrentActor AuthContext caller,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        FriendService.Page<FriendService.FriendEntry> page =
                friends.listFriends(caller.actorId(), intOrZero(limit), cursor);
        return ApiResponse.ok(new SocialPageView<>(
                FriendViews.friends(page.items(), databaseZone),
                page.nextCursor(),
                page.hasMore()));
    }

    /** §3.5 删好友。幂等：本来不是好友也回成功（{@code removed=false}）。 */
    @DeleteMapping("/{targetId}")
    public ApiResponse<FriendRemovedView> remove(@CurrentActor AuthContext caller,
                                                @PathVariable long targetId) {
        boolean removed = friends.removeFriend(caller.actorId(), targetId);
        return ApiResponse.ok(new FriendRemovedView(caller.actorId(), targetId, removed));
    }

    /** §3.6 拉黑。幂等。 */
    @PostMapping("/{targetId}/block")
    public ApiResponse<FriendBlockView> block(@CurrentActor AuthContext caller,
                                              @PathVariable long targetId) {
        FriendService.BlockOutcome outcome = friends.block(caller.actorId(), targetId);
        return ApiResponse.ok(new FriendBlockView(outcome.actorId(), outcome.targetId(),
                outcome.blocked()));
    }

    /**
     * §3.6 解除拉黑。
     *
     * <p>路由与 {@link #remove} 不冲突：{@code DELETE /v1/friends/{id}} 与
     * {@code DELETE /v1/friends/{id}/block} 段数不同，Spring 的模式匹配天然区分。
     */
    @DeleteMapping("/{targetId}/block")
    public ApiResponse<FriendBlockView> unblock(@CurrentActor AuthContext caller,
                                                @PathVariable long targetId) {
        FriendService.BlockOutcome outcome = friends.unblock(caller.actorId(), targetId);
        return ApiResponse.ok(new FriendBlockView(outcome.actorId(), outcome.targetId(),
                outcome.blocked()));
    }

    // ================================================================== 协议翻译

    /**
     * {@code direction} 缺席默认 {@code incoming}。
     *
     * <p>选 incoming 而不是 outgoing 作为默认：这个列表最常见的用途是
     * 「有没有人加我」（点进去看到待处理的红点），而「我发出的请求」是次要的查看动作。
     * 默认值必须写在该写的地方——若让客户端猜，两种客户端会在同一个 URL 上
     * 得到相反的列表。
     */
    private static boolean incomingOnly(String direction) {
        if (direction == null || direction.isBlank()) {
            return true;
        }
        return switch (direction.strip().toLowerCase(Locale.ROOT)) {
            case "incoming" -> true;
            case "outgoing" -> false;
            default -> throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "direction=" + direction + "（可选 incoming / outgoing）");
        };
    }

    /** {@code status} 缺席默认 {@code pending}（只看待处理的）。 */
    private static boolean pendingOnly(String status) {
        if (status == null || status.isBlank()) {
            return true;
        }
        return switch (status.strip().toLowerCase(Locale.ROOT)) {
            case "pending" -> true;
            case "all" -> false;
            default -> throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "status=" + status + "（可选 pending / all）");
        };
    }

    /** {@code limit} 缺席 → 0，由服务层换成默认页大小（见 {@code ConversationService} 同一取舍）。 */
    private static int intOrZero(Integer limit) {
        return limit == null ? 0 : limit;
    }
}
