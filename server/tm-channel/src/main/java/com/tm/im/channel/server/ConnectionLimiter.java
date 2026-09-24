package com.tm.im.channel.server;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.LongAdder;

/**
 * 连接数上限（{@code tm.netty.max-connections}）。
 *
 * <p>为什么需要它：单实例的连接上限真正受制于文件描述符与内存，
 * 而这两样一旦耗尽，症状不是「新连接建不上」，而是<b>整个进程开始随机失败</b>
 * （连不上数据库、写不了日志）。在应用层设一个明确的上限，
 * 让超限表现为「新连接被立刻关闭」这一种可预测的行为。
 *
 * <p>两个实现细节：
 * <ul>
 *   <li>超限时<b>不</b>向下游转发 {@code channelActive}：下游的握手超时任务
 *       等逻辑就没必要启动，省掉一批「马上就没人用的」定时任务。</li>
 *   <li>计数用「进入即加、关闭即减」的成对操作：即使这条连接是被本处理器
 *       关掉的，{@code channelInactive} 一样会触发，因此计数不会泄漏。</li>
 * </ul>
 *
 * <p><b>为什么必须标 {@link ChannelHandler.Sharable}</b>：这个上限是<b>实例级</b>的，
 * 计数器必须被所有连接共用，因此只能有一个实例、被加进每条连接的 pipeline。
 * Netty 默认拒绝把一个未标 {@code @Sharable} 的处理器重复加入多条 pipeline
 * （{@code ChannelPipelineException}），并且是在 {@code initChannel} 阶段就把连接关掉
 * —— 症状是「第一条连接正常，之后所有连接一连上就被断开」，
 * 而日志里只有一行 {@code Failed to initialize a channel}。
 * 本类能满足可共享的前提：它没有任何单连接状态，字段只有线程安全的 {@link LongAdder}。
 */
@ChannelHandler.Sharable
public class ConnectionLimiter extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ConnectionLimiter.class);

    private final int maxConnections;
    private final LongAdder open = new LongAdder();
    private final LongAdder rejected = new LongAdder();

    public ConnectionLimiter(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        int now = open.intValue() + 1;
        open.increment();
        if (now > maxConnections) {
            rejected.increment();
            // 只关闭、不回错误帧：此时连协议都还没确定（分流尚未发生），
            // 无法保证对方能理解我们发出去的任何字节。
            log.warn("连接数已达上限 {}，拒绝新连接 remote={}（累计拒绝 {}）",
                    maxConnections, ctx.channel().remoteAddress(), rejected.sum());
            ctx.close();
            return;
        }
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        open.decrement();
        ctx.fireChannelInactive();
    }

    public int openConnections() {
        return open.intValue();
    }

    public long rejectedConnections() {
        return rejected.sum();
    }
}
