package com.tm.im.channel.handler;

import com.tm.im.channel.codec.Frames;
import com.tm.im.proto.transport.Frame;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 心跳（04-realtime.md §5.3）。
 *
 * <pre>
 * 入站 PING  → 立即回 PONG（同一 req_id，便于客户端配对）
 * 入站 PONG  → 忽略（它已经完成了「证明对端活着」的使命）
 * 读空闲     → 服务端主动发 PING 探活
 * 全空闲     → 断开
 * </pre>
 *
 * <p><b>为什么不依赖 TCP keepalive</b>：keepalive 由内核按小时级间隔探测，
 * 且中间设备（NAT、负载均衡）会静默丢弃闲置连接而不通知任何一端 ——
 * 双方都以为连接还在，直到某一方真的要发数据。应用层心跳是唯一可靠的存活判据。
 *
 * <p><b>为什么服务端也要主动 PING</b>：正常客户端每 30s 发一次 PING，
 * 服务端本不需要主动探测。但「客户端进程被 SIGSTOP」「客户端所在机器挂起」
 * 这类情况下它的定时器也不转了，连接会一直占着资源。
 * 服务端的空闲探测就是这种半死连接的唯一发现途径。
 */
public class HeartbeatHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatHandler.class);

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof Frame frame)) {
            ctx.fireChannelRead(msg);
            return;
        }
        switch (frame.getCmd()) {
            case CMD_PING -> {
                // 心跳在 IO 线程直接答复：它不查任何外部资源，
                // 放进业务线程池反而会让「连接是否活着」依赖于业务队列的拥挤程度。
                ctx.writeAndFlush(Frames.of(Frame.Cmd.CMD_PONG, frame.getReqId(), null));
                log.trace("回 PONG remote={} reqId={}", ctx.channel().remoteAddress(), frame.getReqId());
            }
            case CMD_PONG -> log.trace("收到 PONG remote={}", ctx.channel().remoteAddress());
            default -> ctx.fireChannelRead(frame);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (!(evt instanceof IdleStateEvent event)) {
            ctx.fireUserEventTriggered(evt);
            return;
        }
        if (event.state() == IdleState.ALL_IDLE) {
            // 断开而不是「先 ping 再看」：全空闲已经包含了读数空闲，
            // 说明前面发出去的探活 PING 没有得到任何回应。
            log.info("心跳超时断开 remote={}", ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        // READER_IDLE：长时间没收到任何字节，主动探活一次。
        ctx.writeAndFlush(Frames.of(Frame.Cmd.CMD_PING, 0, null));
        log.debug("读空闲，发探活 PING remote={}", ctx.channel().remoteAddress());
    }
}
