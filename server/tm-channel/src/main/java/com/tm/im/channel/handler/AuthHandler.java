package com.tm.im.channel.handler;

import com.tm.im.channel.codec.Frames;
import com.tm.im.channel.session.ChannelAttributes;
import com.tm.im.channel.session.ChannelAttributes.HandshakeState;
import com.tm.im.channel.session.ConnectionRegistry;
import com.tm.im.channel.session.TmConnection;
import com.tm.im.channel.session.TmSession;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.core.identity.AuthContext;
import com.tm.im.core.identity.IdentityService;
import com.tm.im.proto.transport.ActorType;
import com.tm.im.proto.transport.AuthRequest;
import com.tm.im.proto.transport.AuthResponse;
import com.tm.im.proto.transport.Frame;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 握手与鉴权（04-realtime.md §5.2）：
 * <pre>
 * 1. 连接建立
 * 2. 客户端立即发 AUTH 帧
 * 3. 校验：成功 → AUTH_OK，进入就绪态；失败 → ERROR，然后服务端主动关闭
 * 4. auth-timeout-ms（默认 5s）内没收到 AUTH → 主动断开
 * </pre>
 *
 * <p><b>本类是「铁律」最关键的一处落点</b>（DESIGN §7.3）：鉴权要查库
 * （api_key 要按哈希反查、JWT 也要确认账号状态与被封禁），因此<b>必须</b>
 * 离开 IO 线程执行。同一 worker 线程上通常挂着成百上千条连接，
 * 在这里做一次 50ms 的查询，那上千条连接一起停顿 50ms —— 这类事故的表现是
 * 「高峰期整体卡顿」，而不是「某个请求慢」。
 *
 * <p>由此带来一个不那么直观的状态：收到 AUTH 帧之后、查库返回之前，
 * 连接处于 {@link HandshakeState#AUTHENTICATING}。握手超时任务只在
 * {@code AWAITING_AUTH} 状态下才掐连接（见 {@link #stateOf}），
 * 否则数据库慢一点就会把「正在鉴权」的连接误杀，表现为偶发登录失败。
 *
 * <p><b>为什么是两个计时器而不是一个</b>：本类只负责「已分流但迟迟不发 AUTH」
 * 这一半。「连上后一个字节都不发」那一半由 {@code ProtocolSniffer} 负责——
 * 本类是被分流器动态装进 pipeline 的，在那之前它根本不存在，也无从定时。
 * 两者共享同一个死线（{@link ChannelAttributes#HANDSHAKE_DEADLINE}），
 * 因此「auth-timeout-ms 内必须完成 AUTH」的对外承诺不会被拉成两倍。
 */
public class AuthHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(AuthHandler.class);

    private final IdentityService identityService;
    private final ConnectionRegistry registry;
    private final ThreadPoolExecutor businessExecutor;
    private final long authTimeoutMillis;
    private final int heartbeatSeconds;

    public AuthHandler(IdentityService identityService,
                       ConnectionRegistry registry,
                       ThreadPoolExecutor businessExecutor,
                       long authTimeoutMillis,
                       int heartbeatSeconds) {
        this.identityService = identityService;
        this.registry = registry;
        this.businessExecutor = businessExecutor;
        this.authTimeoutMillis = authTimeoutMillis;
        this.heartbeatSeconds = heartbeatSeconds;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        channel.attr(ChannelAttributes.HANDSHAKE).set(HandshakeState.AWAITING_AUTH);
        scheduleAuthTimeout(ctx);
        log.debug("连接建立 remote={} 等待 AUTH（{}ms 超时）", channel.remoteAddress(), authTimeoutMillis);
        ctx.fireChannelActive();
    }

    private void scheduleAuthTimeout(ChannelHandlerContext ctx) {
        // 不需要保存 future 也不需要在连接关闭时取消：调度用的是该 Channel 自己的
        // EventLoop，连接关闭后它的任务队列随之丢弃。若将来改成共享调度器，
        // 这里必须补 cancel，否则会残留大量指向已关闭连接的任务。
        //
        // 等待时长取「离握手死线还剩多少」而不是「再等一个完整超时」：
        // 死线由 ProtocolSniffer 在连接建立时就记下了，两个阶段共享同一个预算。
        long remainingNanos = Math.max(
                ChannelAttributes.handshakeRemainingNanos(ctx.channel(), authTimeoutMillis), 0);
        ctx.executor().schedule(() -> {
            Channel channel = ctx.channel();
            if (stateOf(channel) != HandshakeState.AWAITING_AUTH) {
                return;
            }
            log.debug("握手超时（{}ms 未收到 AUTH）remote={}", authTimeoutMillis, channel.remoteAddress());
            channel.writeAndFlush(Frames.error(0, ErrorCode.UNAUTHORIZED,
                            "no AUTH frame within " + authTimeoutMillis + "ms"))
                    .addListener(ChannelFutureListener.CLOSE);
        }, remainingNanos, TimeUnit.NANOSECONDS);
    }

    private static HandshakeState stateOf(Channel channel) {
        return ChannelAttributes.state(channel);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof Frame frame)) {
            ctx.fireChannelRead(msg);
            return;
        }
        Channel channel = ctx.channel();
        HandshakeState state = stateOf(channel);

        if (state == HandshakeState.READY) {
            ctx.fireChannelRead(frame);
            return;
        }
        if (state == HandshakeState.AUTHENTICATING) {
            // 已经在查库了还收到帧，只可能是客户端「没等 AUTH_OK 就继续发」。
            // 这里不报错也不丢弃，交给下游：
            //   - PING/PONG 不需要身份，答了不会出错，也避免客户端因心跳无响应而重连；
            //   - 需要身份的命令由 BusinessHandler 回 40101。
            // 反过来「在这里直接断开」会误杀那些合法地 pipelined 的客户端。
            ctx.fireChannelRead(frame);
            return;
        }

        // state == AWAITING_AUTH：第一帧必须是 AUTH
        if (frame.getCmd() != Frame.Cmd.CMD_AUTH) {
            reject(channel, frame.getReqId(), ErrorCode.UNAUTHORIZED,
                    "first frame must be CMD_AUTH, got " + frame.getCmd());
            return;
        }

        AuthRequest request;
        try {
            request = Frames.body(frame, AuthRequest.getDefaultInstance());
        } catch (Frames.FrameBodyException e) {
            reject(channel, frame.getReqId(), ErrorCode.INVALID_PARAMETER, e.getMessage());
            return;
        }

        channel.attr(ChannelAttributes.HANDSHAKE).set(HandshakeState.AUTHENTICATING);
        long reqId = frame.getReqId();
        // 关键：把查库交给业务线程池，IO 线程立即返回。
        com.tm.im.channel.config.ChannelConfiguration.submitBusiness(businessExecutor, channel,
                () -> doAuthenticate(channel, reqId, request));
    }

    /** 在业务线程上执行。所有写回 Channel 的操作都是线程安全的（Netty 内部投递到 EventLoop）。 */
    private void doAuthenticate(Channel channel, long reqId, AuthRequest request) {
        try {
            AuthContext auth = identityService.authenticate(
                    request.getToken(),
                    request.getDeviceId().isBlank() ? null : request.getDeviceId());

            TmSession session = TmSession.of(auth, String.valueOf(channel.remoteAddress()), Instant.now());
            channel.attr(ChannelAttributes.SESSION).set(session);
            channel.attr(ChannelAttributes.HANDSHAKE).set(HandshakeState.READY);

            // 先注册再回 AUTH_OK：反过来的话，客户端收到 AUTH_OK 立刻发消息，
            // 而此刻本节点还没把它登记进路由表 —— 那一瞬间的推送会找不到连接。
            int evicted = registry.register(new TmConnection(channel, session));

            channel.writeAndFlush(Frames.of(Frame.Cmd.CMD_AUTH_OK, reqId, AuthResponse.newBuilder()
                    .setActorId(auth.actorId())
                    .setHandle(auth.handle() == null ? "" : auth.handle())
                    .setActorType(auth.isAgent() ? ActorType.ACTOR_TYPE_AGENT : ActorType.ACTOR_TYPE_HUMAN)
                    .setHeartbeatSec(heartbeatSeconds)
                    .build()));

            log.info("鉴权成功 actorId={} handle={} kind={} device={} remote={}{}",
                    auth.actorId(), auth.handle(), auth.kind(), auth.deviceId(), channel.remoteAddress(),
                    evicted > 0 ? "（顶掉同账号的旧连接）" : "");
        } catch (TmException e) {
            // 业务异常：错误码直接来自 02-auth.md §4 的表，客户端据此决定
            // 「刷新 token 重连」还是「提示用户不要再试」。
            log.info("鉴权失败 code={} detail={} remote={}",
                    e.code(), e.detail(), channel.remoteAddress());
            reject(channel, reqId, e.errorCode(), e.detail());
        } catch (RuntimeException e) {
            // 数据库抖动等：对客户端是 50000（可重试），对我们是必须留堆栈的缺陷线索。
            log.error("鉴权过程异常 remote={}", channel.remoteAddress(), e);
            reject(channel, reqId, ErrorCode.INTERNAL_ERROR, "authentication failed");
        }
    }

    /**
     * 回 ERROR 并关闭连接。
     *
     * <p>顺序不能反：先 close 再 write 的话，客户端只会看到「连接被断开」，
     * 拿不到错误码，于是只能盲目重连 —— 而 40103（token 过期）与 40301（账号停用）
     * 的正确处置恰恰是相反的（前者刷新后重连，后者停止重连）。
     */
    private void reject(Channel channel, long reqId, ErrorCode code, String detail) {
        channel.writeAndFlush(Frames.error(reqId, code, detail))
                .addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        TmSession session = ChannelAttributes.session(ctx.channel());
        if (session != null) {
            // unregister 幂等（见 LocalConnectionRegistry），因此顶号后的这次调用是安全的。
            registry.unregister(new TmConnection(ctx.channel(), session));
            log.debug("连接关闭 actorId={} remote={} 当前在线={}",
                    session.actorId(), ctx.channel().remoteAddress(), registry.size());
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // 常见的到这里的原因：对端直接断开（IOException/Connection reset）、
        // 或帧解析失败（CorruptedFrameException，由 Netty 的 ProtobufDecoder 抛出）。
        // 一律按「这条连接不能用了」处理并关闭，避免半死连接长期占用资源。
        log.warn("连接异常关闭 remote={} cause={}: {}",
                ctx.channel().remoteAddress(), cause.getClass().getSimpleName(), cause.getMessage());
        ctx.close();
    }
}
