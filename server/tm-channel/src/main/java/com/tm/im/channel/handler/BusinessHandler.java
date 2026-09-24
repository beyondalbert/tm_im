package com.tm.im.channel.handler;

import com.tm.im.channel.codec.Frames;
import com.tm.im.channel.session.ChannelAttributes;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.proto.transport.Frame;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 业务命令分发。
 *
 * <p><b>M2 阶段这里只有「已就绪 + 未实现」两种结果</b>：M2 的验收范围是
 * 协议/鉴权/心跳/注册表，{@code SEND}、{@code READ}、{@code SYNC} 的实现在 M3。
 * 对这三者回的是 50000（内部错误，可重试），而<b>不是静默忽略</b>：
 * 静默忽略会让客户端一直等 ACK，最终表现为「消息发出去没反应」这种
 * 最难排查的现象——看起来像丢包，实际是服务端根本没打算回。
 *
 * <p><b>本类不做任何阻塞操作</b>（DESIGN §7.3）：它只是分发。
 * M3 接上 MessageService 之后，每个分支都必须把工作交给业务线程池，
 * 这一条在 {@link AuthHandler} 里有完整的示例（见其类注释）。
 */
public class BusinessHandler extends SimpleChannelInboundHandler<Frame> {

    private static final Logger log = LoggerFactory.getLogger(BusinessHandler.class);

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Frame frame) {
        // 到这里连接可能还没完成鉴权（心跳之外的帧在 AUTHENTICATING 窗口里会被放行）。
        boolean authenticated = ChannelAttributes.session(ctx.channel()) != null;

        switch (frame.getCmd()) {
            case CMD_AUTH -> {
                // 到达这里说明已经处于 READY：重复 AUTH 属于客户端协议实现问题。
                // 回 40000 而不是直接忽略，让客户端能立刻发现自己的 bug。
                ctx.writeAndFlush(Frames.error(frame.getReqId(), ErrorCode.BAD_REQUEST,
                        "already authenticated"));
            }
            case CMD_SEND, CMD_READ, CMD_SYNC -> {
                if (!authenticated) {
                    ctx.writeAndFlush(Frames.error(frame.getReqId(), ErrorCode.UNAUTHORIZED,
                            "authentication not completed"));
                    return;
                }
                // M3 在这里接 MessageService / ReadService / SyncService。
                log.warn("收到尚未实现的命令 cmd={} actorId={} —— M3 实现后本分支应被替换",
                        frame.getCmd(), ChannelAttributes.session(ctx.channel()).actorId());
                ctx.writeAndFlush(Frames.error(frame.getReqId(), ErrorCode.INTERNAL_ERROR,
                        "command not implemented yet: " + frame.getCmd()));
            }
            case CMD_PING, CMD_PONG -> {
                // 正常情况下被 HeartbeatHandler 消化，走不到这里。
                // 保留分支是为了让「新增一条到达路径」时不会变成 Unknown command。
                log.debug("心跳帧到达分发层（异常路径）cmd={}", frame.getCmd());
            }
            default -> {
                // CMD_UNKNOWN（0，保留值）与客户端自造的非法命令字。
                ctx.writeAndFlush(Frames.error(frame.getReqId(), ErrorCode.BAD_REQUEST,
                        "unsupported cmd: " + frame.getCmdValue()));
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("业务处理异常，关闭连接 remote={}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
