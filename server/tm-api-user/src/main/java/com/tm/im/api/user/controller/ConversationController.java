package com.tm.im.api.user.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tm.im.api.user.auth.CurrentActor;
import com.tm.im.api.user.view.AddMembersRequest;
import com.tm.im.api.user.view.ConversationDetailView;
import com.tm.im.api.user.view.ConversationPageView;
import com.tm.im.api.user.view.ConversationTitleView;
import com.tm.im.api.user.view.ConversationViews;
import com.tm.im.api.user.view.DirectConversationRequest;
import com.tm.im.api.user.view.DirectConversationView;
import com.tm.im.api.user.view.GroupConversationRequest;
import com.tm.im.api.user.view.GroupConversationView;
import com.tm.im.api.user.view.IncrementalView;
import com.tm.im.api.user.view.MarkReadRequest;
import com.tm.im.api.user.view.MemberAddView;
import com.tm.im.api.user.view.MemberRemovedView;
import com.tm.im.api.user.view.MemberRoleView;
import com.tm.im.api.user.view.MessagePageView;
import com.tm.im.api.user.view.MessageViews;
import com.tm.im.api.user.view.ReadView;
import com.tm.im.api.user.view.SendMessageRequest;
import com.tm.im.api.user.view.SendResultView;
import com.tm.im.api.user.view.UpdateMemberRoleRequest;
import com.tm.im.api.user.view.UpdateTitleRequest;
import com.tm.im.common.api.ApiResponse;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.core.conversation.ConversationService;
import com.tm.im.core.identity.AuthContext;
import com.tm.im.core.message.MessageCommandPort;
import com.tm.im.core.message.MessageService;
import com.tm.im.domain.enums.MessageType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.util.Locale;

/**
 * 会话与消息（03-rest-api.md §4.1–§4.9）。
 *
 * <p><b>本控制器里没有任何业务判断</b>：谁能发、能不能发、成员资格、上限、页码收敛
 * 全在 {@link ConversationService} 与 {@code MessageService} 里。这里只做三件机械的事——
 * 把 HTTP 的路径/查询参数/请求体翻译成服务入参、把服务出参翻译成视图、
 * 以及把<b>协议层</b>的写法差异（{@code msg_type} 是名字、{@code content} 是 JSON 节点）
 * 转成领域类型。
 *
 * <p>那条「只做机械的事」的边界恰好是：**能翻译的在这里，需要判断的在上游**。
 * 所以 {@link #msgType(String)} 把 {@code "TEXT"} 转成枚举（转换本身无需判断，
 * 但它对应哪种错误码需要，所以失败时抛 40007 而不是 IllegalArgumentException——
 * 后者会变成 500），而「SYSTEM 消息客户端不许发」那条规则不在这里，
 * 它在 {@code MessageService} 里（见下面对 {@code fromClient} 的说明）。
 */
@RestController
@RequestMapping("/v1/conversations")
public class ConversationController {

    private final ConversationService conversations;
    private final MessageCommandPort messages;
    private final ZoneId databaseZone;

    public ConversationController(ConversationService conversations,
                                  MessageCommandPort messages,
                                  ZoneId databaseZone) {
        this.conversations = conversations;
        this.messages = messages;
        this.databaseZone = databaseZone;
    }

    // ================================================================== 建会话

    /** §4.1 获取或创建单聊会话。幂等：重复调用返回同一条（{@code created=false}）。 */
    @PostMapping("/direct")
    public ApiResponse<DirectConversationView> openDirect(
            @CurrentActor AuthContext caller,
            @RequestBody DirectConversationRequest request) {
        return ApiResponse.ok(ConversationViews.direct(
                conversations.openDirect(caller.actorId(), request.peer()), databaseZone));
    }

    /** §4.2 建群。 */
    @PostMapping("/group")
    public ApiResponse<GroupConversationView> createGroup(
            @CurrentActor AuthContext caller,
            @RequestBody GroupConversationRequest request) {
        return ApiResponse.ok(ConversationViews.group(
                conversations.createGroup(caller.actorId(), request.title(), request.members()),
                databaseZone));
    }

    // ================================================================== 读会话

    /** §4.3 我的会话列表（按最近活跃倒序）。 */
    @GetMapping
    public ApiResponse<ConversationPageView> list(
            @CurrentActor AuthContext caller,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return ApiResponse.ok(ConversationViews.conversationPage(
                conversations.listConversations(caller.actorId(), intOrZero(limit), cursor),
                databaseZone));
    }

    /** §4.4 会话详情（含成员列表）。 */
    @GetMapping("/{convId}")
    public ApiResponse<ConversationDetailView> detail(
            @CurrentActor AuthContext caller,
            @PathVariable long convId) {
        return ApiResponse.ok(ConversationViews.detail(
                conversations.detail(caller.actorId(), convId), databaseZone));
    }

    // ================================================================== 消息

    /**
     * §4.5 发消息。
     *
     * <p>响应里的一切都来自<b>落库后的那一行</b>（见 {@code SendResultView}），
     * 所以「用同一个 {@code client_msg_id} 再发一次，返回完全相同的响应」是结构性成立的，
     * 不需要在这里判断是不是重放。
     */
    @PostMapping("/{convId}/messages")
    public ApiResponse<SendResultView> send(
            @CurrentActor AuthContext caller,
            @PathVariable long convId,
            @RequestBody SendMessageRequest request) {
        MessageService.SendOutcome outcome = messages.send(new MessageService.SendCommand(
                convId,
                caller.actorId(),
                requireClientMsgId(request.clientMsgId()),
                msgType(request.msgType()),
                contentJson(request.content()),
                request.replyTo() == null ? 0L : request.replyTo(),
                // 这是**不可信来源**：所有帧/请求都经这里进来。
                // 它让 MessageService 能一手拦下「客户端伪造 SYSTEM 消息」
                // （SYSTEM 豁免好友校验，见 DESIGN §11.6），而不是 REST 与长连接各拦一道。
                true));
        return ApiResponse.ok(MessageViews.toSendResult(outcome.message(), databaseZone));
    }

    /**
     * §4.6 / §4.7 拉消息：不带 {@code since_seq} 是「最新一页、按 seq 倒序」（翻历史），
     * 带 {@code since_seq} 是「seq 大于它的消息、升序」（断线补拉）。
     *
     * <p>返回类型写成 {@code ApiResponse<?>} 是因为<b>同一个路径有两种响应形状</b>
     * （{@code next_cursor} 与 {@code latest_seq} 各自只属于一种模式）。两种形状各自是一个
     * record（{@link MessagePageView} / {@link IncrementalView}），所以类型安全没有丢——
     * 丢掉的只是这一个方法签名上的静态类型。合并成一个「两种字段都有、各有一半是 null」
     * 的响应体更糟：客户端会开始猜「next_cursor 为 null 是不是表示没有更多了」。
     */
    @GetMapping("/{convId}/messages")
    public ApiResponse<?> messages(
            @CurrentActor AuthContext caller,
            @PathVariable long convId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(name = "since_seq", required = false) Long sinceSeq) {
        int pageSize = intOrZero(limit);
        boolean hasCursor = cursor != null && !cursor.isBlank();
        if (hasCursor && sinceSeq != null) {
            // 两种模式表达的是完全不同的问题（「更早的那一页」vs「我断线之后的新消息」），
            // 而它们的参数同时出现时该听谁的没有定义。不猜：让客户端自己选一个。
            throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "cursor 与 since_seq 不能同时给：前者是翻历史（倒序），后者是断线补拉（升序）");
        }
        if (sinceSeq != null) {
            return ApiResponse.ok(ConversationViews.incremental(
                    conversations.incremental(caller.actorId(), convId, sinceSeq, pageSize), databaseZone));
        }
        return ApiResponse.ok(ConversationViews.messagePage(
                conversations.history(caller.actorId(), convId, pageSize, cursor), databaseZone));
    }

    /** §4.8 上报已读。幂等：游标只前进（回的是生效后的值）。 */
    @PostMapping("/{convId}/read")
    public ApiResponse<ReadView> markRead(
            @CurrentActor AuthContext caller,
            @PathVariable long convId,
            @RequestBody MarkReadRequest request) {
        if (request.lastReadSeq() == null) {
            throw new TmException(ErrorCode.MISSING_PARAMETER, "last_read_seq 缺失");
        }
        return ApiResponse.ok(ConversationViews.read(
                conversations.markRead(caller.actorId(), convId, request.lastReadSeq())));
    }

    // ================================================================== 群成员管理（§4.9）

    /** §4.9 加人。幂等：已经在群里的从 {@code already_members} 里回，不算错。 */
    @PostMapping("/{convId}/members")
    public ApiResponse<MemberAddView> addMembers(
            @CurrentActor AuthContext caller,
            @PathVariable long convId,
            @RequestBody AddMembersRequest request) {
        return ApiResponse.ok(ConversationViews.memberAdd(
                conversations.addMembers(caller.actorId(), convId, request.members()), databaseZone));
    }

    /** §4.9 踢人。目标不在群里回 40908（不是幂等成功，见服务端注释）。 */
    @DeleteMapping("/{convId}/members/{actorId}")
    public ApiResponse<MemberRemovedView> removeMember(
            @CurrentActor AuthContext caller,
            @PathVariable long convId,
            @PathVariable long actorId) {
        return ApiResponse.ok(ConversationViews.memberRemoved(
                conversations.removeMember(caller.actorId(), convId, actorId)));
    }

    /**
     * §4.9 退群。
     *
     * <p>{@code me} 是字面量而不是一个 actor_id：路由里出现非数字时 Tomcat 会回 400
     * （框架自己的错误体，不是我们的信封），而 {@code /members/me} 这种写法不需要
     * 客户端先查出自己的 id，也不给对方一个「踢别人却说成退群」的形状。
     * 它与 {@code /{actorId}} 不冲突：Spring 的模式比较里**字面量段比变量段更具体**，
     * 所以 {@code /members/me} 总是选中这个映射，与两个映射的声明顺序无关。
     */
    @DeleteMapping("/{convId}/members/me")
    public ApiResponse<MemberRemovedView> leaveGroup(
            @CurrentActor AuthContext caller,
            @PathVariable long convId) {
        return ApiResponse.ok(ConversationViews.memberRemoved(
                conversations.leaveGroup(caller.actorId(), convId)));
    }

    /** §4.9 改群名。幂等：同名重复上报不写库、也不产生系统消息。 */
    @PatchMapping("/{convId}")
    public ApiResponse<ConversationTitleView> updateTitle(
            @CurrentActor AuthContext caller,
            @PathVariable long convId,
            @RequestBody UpdateTitleRequest request) {
        return ApiResponse.ok(ConversationViews.title(
                conversations.renameGroup(caller.actorId(), convId, request.title())));
    }

    /**
     * §4.9 设置角色（{@code role=1} 即转让群主）。
     *
     * <p>只判「有没有」不判「对不对」：{@code role=0/4} 是取值非法（40002），
     * 与字段缺失（40001）不是同一件事，而后者只有这里能看出——枚举反查在服务里，
     * 它对 {@code null} 会顺理成章地当成普通越界值。
     */
    @PatchMapping("/{convId}/members/{actorId}")
    public ApiResponse<MemberRoleView> updateMemberRole(
            @CurrentActor AuthContext caller,
            @PathVariable long convId,
            @PathVariable long actorId,
            @RequestBody UpdateMemberRoleRequest request) {
        if (request.role() == null) {
            throw new TmException(ErrorCode.MISSING_PARAMETER, "role 缺失（1=OWNER 2=ADMIN 3=MEMBER）");
        }
        return ApiResponse.ok(ConversationViews.memberRole(
                conversations.changeRole(caller.actorId(), convId, actorId, request.role())));
    }

    // ================================================================== 协议翻译

    /**
     * {@code limit} 缺席 → 0，由 {@code ConversationProperties.clampPageSize} 换成默认值。
     *
     * <p>刻意不在这里选默认值：那个值要被 {@code verify_config_template} 校验，
     * 只能有一处定义（见 {@code MessageProperties.clampPullSize} 同一取舍）。
     */
    private static int intOrZero(Integer limit) {
        return limit == null ? 0 : limit;
    }

    /** §4.5 的 {@code msg_type} 是<b>名字</b>（{@code "TEXT"}），大小写不敏感。 */
    private static MessageType msgType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new TmException(ErrorCode.MISSING_PARAMETER, "msg_type 缺失");
        }
        try {
            return MessageType.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // 与长连接的 BusinessHandler.convertType 同一取舍：不认识的值不猜成 TEXT，
            // 否则一条图片消息会被当成文字存进去，而两边都看不到错。
            throw new TmException(ErrorCode.INVALID_MSG_TYPE,
                    "msg_type=" + raw + "（可选 TEXT / IMAGE / SYSTEM）");
        }
    }

    /**
     * §4.5 的 {@code client_msg_id} 是必填的：它是幂等键，而「弱网重试」正是这个接口最常发生的事。
     * 缺了它，客户端重试就会产生重复消息。
     *
     * <p>长度上限在 {@code MessageService} 里判（两条链路共用一个规则），这里只判「有没有」。
     */
    private static String requireClientMsgId(String clientMsgId) {
        if (clientMsgId == null || clientMsgId.isBlank()) {
            throw new TmException(ErrorCode.MISSING_PARAMETER,
                    "client_msg_id 不能为空（它是幂等键，缺了它重试就会发重）");
        }
        return clientMsgId;
    }

    /**
     * {@code content} 原样交给 {@code MessageService} 校验结构。
     *
     * <p>用 {@code toString()} 而不是重新序列化：{@link JsonNode#toString()} 输出的是<b>紧凑且保序</b>的
     * JSON，与客户端发来的字段顺序一致。用 {@code Json.write(node)} 也一样，
     * 但那样会多一次解析-再序列化（这里是全系统最热的写路径）。
     */
    private static String contentJson(JsonNode content) {
        if (content == null || content.isNull()) {
            throw new TmException(ErrorCode.MISSING_PARAMETER, "content 不能为空");
        }
        return content.toString();
    }
}
