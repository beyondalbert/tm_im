package com.tm.im.channel.handler;

import com.tm.im.channel.codec.Frames;
import com.tm.im.channel.codec.TransportMessageMapper;
import com.tm.im.channel.config.ChannelConfiguration;
import com.tm.im.channel.session.ChannelAttributes;
import com.tm.im.channel.session.TmSession;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.core.message.MessageCommandPort;
import com.tm.im.core.message.MessageService;
import com.tm.im.domain.entity.Message;
import com.tm.im.domain.enums.MessageType;
import com.tm.im.proto.transport.ConvCursor;
import com.tm.im.proto.transport.Frame;
import com.tm.im.proto.transport.ReadRequest;
import com.tm.im.proto.transport.SendAck;
import com.tm.im.proto.transport.SendRequest;
import com.tm.im.proto.transport.SyncEnd;
import com.tm.im.proto.transport.SyncRequest;
import com.tm.im.proto.transport.SyncResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 业务命令分发。
 *
 * <p><b>本类在 IO 线程上只做两件事：拆帧、把任务丢给业务线程池</b>（DESIGN §7.3）。
 * 任何在这里直接查库的写法都会让一条慢查询拖住该 EventLoop 上的<b>所有</b>连接——
 * 这正是 §7.3 那条铁律要禁止的事。{@link AuthHandler} 的类注释里有同一条理由。
 *
 * <p><b>{@code CMD_SYNC} 的读取路径已接通</b>（DESIGN §10.2）。一次请求恰好回一帧
 * {@code CMD_SYNC_RESP(16)}，且<b>只在 {@code has_more=false} 时</b>再跟一帧
 * {@code CMD_SYNC_END(15)}。在 {@code has_more=true} 时发 END 是这里最容易犯的错：
 * 客户端会以为已经追平并开始接收实时推送，而中间缺的那段再也没人补。
 * 两个方向各由一个用例钉住（补齐时会收到 END；还有下一轮时<b>不会</b>收到）。
 *
 * <p>业务侧只回一个 {@link MessageService.SyncOutcome}，帧怎么发由本类决定：
 * 「跳过了哪些会话」（非成员或会话已不存在）写在 END 的 {@code message} 里——
 * 这样客户端才能发现自己本地还留着一个已退群的会话游标，而不是永远带着它重连。
 *
 * <p><b>方向门禁</b>：协议里有一部分命令只由服务端发出（{@code CMD_PUSH}、
 * {@code CMD_SYNC_RESP}、{@code CMD_SEND_ACK}…）。它们如果从客户端发来，
 * 属于客户端实现错误（最常见的成因是把收到的响应原样回显），这里会明确说清
 * 是哪一类错——而不是落进 {@code default} 分支被笼统地当成「未知命令」。
 *
 * <p><b>错误映射只有一处</b>：{@link TmException} 携带的错误码原样回给客户端，
 * 其余异常一律 50000 并记 ERROR 日志。REST 侧有另一个同样职责的处理器，
 * 两边都读同一个 {@link ErrorCode}，因此「同一个非法请求走 HTTP 与走 WS 得到同一个错误码」
 * 是结构上成立的，而不是靠人比对。
 */
public class BusinessHandler extends SimpleChannelInboundHandler<Frame> {

    private static final Logger log = LoggerFactory.getLogger(BusinessHandler.class);

    private final MessageCommandPort messages;
    private final ThreadPoolExecutor businessExecutor;
    private final ZoneId databaseZone;
    private final TransportMessageMapper messageMapper;

    /**
     * @param databaseZone 库里 {@code DATETIME} 所代表的时区（{@code tm.time.zone}）。
     *                     回执里的 {@code created_at_ms} 必须用它换算：库里存的是不带
     *                     时区的墙上时间，用 UTC 去解释会让客户端看到的时间差 8 小时，
     *                     而消息内容、顺序、未读数全部正常——最难发现的一类错。
     * @param messageMapper 领域消息 → 传输消息的唯一转换点。续传帧里的消息
     *                     与推送帧里的必须是同一种翻译（同一个 {@code Message} 对象
     *                     可能先经过它发 PUSH、断线后又被 SYNC 补一次），
     *                     所以这里复用同一个组件而不是自己拼字段。
     */
    public BusinessHandler(MessageCommandPort messages, ThreadPoolExecutor businessExecutor,
                          ZoneId databaseZone, TransportMessageMapper messageMapper) {
        this.messages = messages;
        this.businessExecutor = businessExecutor;
        this.databaseZone = databaseZone;
        this.messageMapper = messageMapper;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Frame frame) {
        // 到这里连接可能还没完成鉴权（心跳之外的帧在 AUTHENTICATING 窗口里会被放行）。
        TmSession session = ChannelAttributes.session(ctx.channel());
        boolean authenticated = session != null;

        // 方向门禁必须在分发之前：服务端专用命令（PUSH/SYNC_RESP/SEND_ACK…）从客户端发来
        // 是「客户端把响应当请求回显」这类实现错误，混在 default 分支里只能得到
        // 「unsupported cmd」——那句话会把排查方向指向「服务端不认识这个命令」，正好指反。
        Frames.Direction direction = Frames.direction(frame.getCmd());
        if (!direction.fromClient()) {
            respond(ctx, frame.getReqId(), ErrorCode.BAD_REQUEST,
                    direction == Frames.Direction.SERVER_TO_CLIENT
                            ? frame.getCmd() + " 是服务端专用命令，客户端不得发送（04-realtime.md §2.1）"
                            : "unsupported cmd: " + frame.getCmdValue());
            return;
        }

        switch (frame.getCmd()) {
            case CMD_AUTH -> {
                // 到达这里说明已经处于 READY：重复 AUTH 属于客户端协议实现问题。
                // 回 40000 而不是直接忽略，让客户端能立刻发现自己的 bug。
                respond(ctx, frame.getReqId(), ErrorCode.BAD_REQUEST, "already authenticated");
            }
            case CMD_SEND -> {
                if (!authenticated) {
                    respond(ctx, frame.getReqId(), ErrorCode.UNAUTHORIZED,
                            "authentication not completed");
                    return;
                }
                handleSend(ctx, frame, session);
            }
            case CMD_READ -> {
                if (!authenticated) {
                    respond(ctx, frame.getReqId(), ErrorCode.UNAUTHORIZED,
                            "authentication not completed");
                    return;
                }
                handleRead(ctx, frame, session);
            }
            case CMD_SYNC -> {
                if (!authenticated) {
                    respond(ctx, frame.getReqId(), ErrorCode.UNAUTHORIZED,
                            "authentication not completed");
                    return;
                }
                handleSync(ctx, frame, session);
            }
            case CMD_PING, CMD_PONG -> {
                // 正常情况下被 HeartbeatHandler 消化，走不到这里。
                // 保留分支是为了让「新增一条到达路径」时不会变成 Unknown command。
                log.debug("心跳帧到达分发层（异常路径）cmd={}", frame.getCmd());
            }
            default -> {
                // 走到这里说明该命令字在 Frames.direction 里被声明为「客户端可发」，
                // 但这里没有对应分支 —— 这是服务端自己的疏漏（新加命令字时漏改分发），
                // 属于 5xx，不是客户端错误。用 40000 会是撒谎，用静默会是灾难。
                log.error("命令 {} 已声明客户端可发，但分发未实现", frame.getCmd());
                respond(ctx, frame.getReqId(), ErrorCode.INTERNAL_ERROR,
                        "command allowed from client but not dispatched: " + frame.getCmd());
            }
        }
    }

    private void handleSend(ChannelHandlerContext ctx, Frame frame, TmSession session) {
        SendRequest request;
        try {
            request = Frames.body(frame, SendRequest.getDefaultInstance());
        } catch (Frames.FrameBodyException e) {
            // 能走到这里说明连接已鉴权，那么载荷格式错就是客户端的问题（40002）
            respond(ctx, frame.getReqId(), ErrorCode.INVALID_PARAMETER, e.getMessage());
            return;
        }

        long reqId = frame.getReqId();
        ChannelConfiguration.submitBusiness(businessExecutor, ctx.channel(), () -> {
            try {
                MessageService.SendOutcome outcome = messages.send(new MessageService.SendCommand(
                        request.getConvId(),
                        session.actorId(),
                        request.getClientMsgId(),
                        convertType(request.getMsgType()),
                        request.getContentJson(),
                        request.getReplyTo(),
                        // 帧里的东西一律来自不可信客户端。有了这个标记，
                        // 「客户端不许发 SYSTEM 消息」这条规则只需在 MessageService 里写一遍，
                        // 而不是在 Netty handler 与 REST 控制器里各拦一道（两道就有一道会漏）。
                        true));
                // created_at_ms 是展示用的 Unix 毫秒。库里存的是 DATETIME（不含时区），
                // 它的含义由 tm.time.zone 定义（MessageService 落库时就用那个时区取值），
                // 所以这里必须用同一个时区换算回去，否则客户端看到的时间会差 8 小时。
                long createdAtMs = outcome.message().getCreatedAt() == null
                        ? 0L
                        : outcome.message().getCreatedAt()
                                .atZone(databaseZone)
                                .toInstant()
                                .toEpochMilli();
                ctx.writeAndFlush(Frames.of(Frame.Cmd.CMD_SEND_ACK, reqId, SendAck.newBuilder()
                        .setConvId(outcome.message().getConvId())
                        .setSeq(outcome.message().getSeq())
                        .setMessageId(outcome.message().getId())
                        .setCreatedAtMs(createdAtMs)
                        .setClientMsgId(request.getClientMsgId())
                        .build()));
            } catch (TmException e) {
                respondAsync(ctx, reqId, e.errorCode(), e.detail());
            } catch (RuntimeException e) {
                // 50000 类异常带堆栈（TmException 只对 5xxxx 开堆栈），这里再记一次
                // 是为了让「业务线程里抛了什么」在日志里与 IO 线程的日志区分开
                log.error("发送消息失败 actorId={} convId={}", session.actorId(),
                        request.getConvId(), e);
                respondAsync(ctx, reqId, ErrorCode.INTERNAL_ERROR, null);
            }
        });
    }

    private void handleRead(ChannelHandlerContext ctx, Frame frame, TmSession session) {
        ReadRequest request;
        try {
            request = Frames.body(frame, ReadRequest.getDefaultInstance());
        } catch (Frames.FrameBodyException e) {
            respond(ctx, frame.getReqId(), ErrorCode.INVALID_PARAMETER, e.getMessage());
            return;
        }

        long reqId = frame.getReqId();
        ChannelConfiguration.submitBusiness(businessExecutor, ctx.channel(), () -> {
            try {
                messages.markRead(request.getConvId(), session.actorId(), request.getLastReadSeq());
                // 成功时<b>不回帧</b>：协议里 CMD_READ 只有请求、没有响应命令
                // （04-realtime.md §2.1），未读数由 REST 返回（§4.8）。
                // 失败则必须回 ERROR（下面两个 catch）——「失败也不回」会让
                // 客户端的未读状态静默不一致。
                log.debug("已读上报成功 actorId={} convId={} lastReadSeq={}",
                        session.actorId(), request.getConvId(), request.getLastReadSeq());
            } catch (TmException e) {
                respondAsync(ctx, reqId, e.errorCode(), e.detail());
            } catch (RuntimeException e) {
                log.error("已读上报失败 actorId={} convId={}", session.actorId(),
                        request.getConvId(), e);
                respondAsync(ctx, reqId, ErrorCode.INTERNAL_ERROR, null);
            }
        });
    }

    /**
     * 断点续传（04-realtime.md §6.2）：一帧 {@code SYNC_RESP}，必要时再一帧 {@code SYNC_END}。
     *
     * <p>两帧的顺序不能反、{@code req_id} 必须都是请求的那个值：客户端靠
     * 「同一个 {@code req_id} 的 RESP 后面还跟不跟 END」来判断本轮是否结束（§6.2）。
     * 这里用「先 write 再 writeAndFlush」而不是两次 {@code writeAndFlush}：
     * 两帧会落在同一个 TCP 段里，客户端不会看到「RESP 到了而 END 还在路上」的中间态。
     */
    private void handleSync(ChannelHandlerContext ctx, Frame frame, TmSession session) {
        SyncRequest request;
        try {
            request = Frames.body(frame, SyncRequest.getDefaultInstance());
        } catch (Frames.FrameBodyException e) {
            respond(ctx, frame.getReqId(), ErrorCode.INVALID_PARAMETER, e.getMessage());
            return;
        }

        long reqId = frame.getReqId();
        ChannelConfiguration.submitBusiness(businessExecutor, ctx.channel(), () -> {
            try {
                MessageService.SyncOutcome outcome = messages.sync(new MessageService.SyncCommand(
                        session.actorId(), cursorsOf(request), request.getLimit()));

                SyncResponse.Builder response = SyncResponse.newBuilder()
                        .setHasMore(outcome.hasMore())
                        .setTruncated(outcome.truncated());
                for (Message message : outcome.messages()) {
                    response.addMessages(messageMapper.toTransport(message));
                }
                Frame respFrame = Frames.of(Frame.Cmd.CMD_SYNC_RESP, reqId, response.build());

                if (outcome.hasMore()) {
                    // 本轮没补齐：只回 RESP。绝不回 END（04-realtime.md §6.2）——
                    // 客户端会在还没补齐的情况下以为已经追平。
                    ctx.writeAndFlush(respFrame);
                    return;
                }

                SyncEnd.Builder end = SyncEnd.newBuilder()
                        .setOk(true)
                        .setConvSynced(outcome.convsSynced());
                if (!outcome.skippedConvs().isEmpty()) {
                    // 跳过是「不因此失败」，不是「不重要」：不说出来的话，
                    // 客户端会永远带着那个已退群的游标重连，而没有任何人知道这件事。
                    end.setMessage("skipped " + outcome.skippedConvs().size()
                            + " cursor(s) you cannot access: " + outcome.skippedConvs());
                }
                ctx.write(respFrame);
                ctx.writeAndFlush(Frames.of(Frame.Cmd.CMD_SYNC_END, reqId, end.build()));
            } catch (TmException e) {
                respondAsync(ctx, reqId, e.errorCode(), e.detail());
            } catch (RuntimeException e) {
                log.error("续传失败 actorId={} cursors={}", session.actorId(),
                        request.getCursorsCount(), e);
                respondAsync(ctx, reqId, ErrorCode.INTERNAL_ERROR, null);
            }
        });
    }

    /** protobuf 游标 → 领域游标。转换处不做校验：规则只有一处，在 {@link MessageService} 里。 */
    private static List<MessageService.SyncCursor> cursorsOf(SyncRequest request) {
        List<MessageService.SyncCursor> cursors = new ArrayList<>(request.getCursorsCount());
        for (ConvCursor cursor : request.getCursorsList()) {
            cursors.add(new MessageService.SyncCursor(cursor.getConvId(), cursor.getSinceSeq()));
        }
        return cursors;
    }

    /** 同步回错（IO 线程上）。 */
    private void respond(ChannelHandlerContext ctx, long reqId, ErrorCode code, String detail) {
        ctx.writeAndFlush(Frames.error(reqId, code, detail));
    }

    /** 业务线程上回错：同样允许直接写，Netty 会把写操作投递回该 Channel 的 EventLoop。 */
    private void respondAsync(ChannelHandlerContext ctx, long reqId, ErrorCode code, String detail) {
        ctx.writeAndFlush(Frames.error(reqId, code, detail));
    }

    /**
     * protobuf 的 {@code MsgType} → 领域枚举。
     *
     * <p>未知值返回 {@code null}，由 {@link MessageService} 判为 40007——
     * 转换处不做「默认按 TEXT 处理」这种兜底：那会让一个新客户端发来的
     * 图片消息被当成文字存进去，而且两边都看不到错。
     */
    private static MessageType convertType(com.tm.im.proto.transport.MsgType protoType) {
        return switch (protoType) {
            case MSG_TYPE_TEXT -> MessageType.TEXT;
            case MSG_TYPE_IMAGE -> MessageType.IMAGE;
            case MSG_TYPE_SYSTEM -> MessageType.SYSTEM;
            case MSG_TYPE_UNKNOWN, UNRECOGNIZED -> null;
        };
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("业务处理异常，关闭连接 remote={}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
