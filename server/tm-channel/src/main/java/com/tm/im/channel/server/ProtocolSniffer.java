package com.tm.im.channel.server;

import com.tm.im.channel.session.ChannelAttributes;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 同端口协议分流：一个端口同时服务 WebSocket 与原生 TCP。
 *
 * <p><b>判据及其成立条件</b>：原生 TCP 以 4 字节大端长度开头，而单帧上限
 * {@code tm.netty.max-frame-bytes} 被约束在 16MB 以下，于是长度的高位字节恒为
 * {@code 0x00}；HTTP 请求行以方法名开头（{@code GET}=0x47、{@code POST}=0x50、
 * {@code HEAD}=0x48、{@code DELETE}=0x44…），首字节都是 ASCII 字母。
 * 因此「首字节是否为 0」是完备判据 —— 前提是那个 16MB 约束被真正强制
 * （见 {@code NettyServer} 的启动校验，它把这条注释变成启动失败）。
 *
 * <p><b>为什么是 {@link ChannelInboundHandlerAdapter} 而不是 {@code ByteToMessageDecoder}</b>：
 * 后者会把读进来的字节收进自己的累积缓冲，而「被移除时剩余字节会被转交下游」
 * 属于它的内部实现细节（不同版本/不同移除时机下并不一致）。这里第一个字节
 * 只是<b>瞄一眼</b>（{@code getUnsignedByte}，不移动读指针），装好目标协议的处理器后
 * 把<b>整个 ByteBuf</b> 原样转发下去。于是「分流会不会吞掉第一条消息的前几个字节」
 * 这一问题根本不存在 —— 而它恰好是最难定位的一类：症状是
 * 「每个连接的第一条消息丢失，之后一切正常」。
 *
 * <p>同理，本类<b>不</b>把自己从 pipeline 里摘除。摘除会额外引入两个顺序问题
 * （剩余字节转交给谁、摘除后从哪个 context 继续 fire），正确处理它们都得依赖
 * Netty 内部细节。留在 pipeline 里只多一次布尔判断，代价可以忽略。
 *
 * <p><b>本类还负责两个只有它才能做的事</b>（因为它被装在分流之前，
 * 是唯一「每条连接都会经过、且时序最早」的位置）：
 *
 * <ol>
 *   <li><b>回放 {@code channelActive}</b>。动态装进来的处理器<b>收不到</b>
 *       「已经发生过的」{@code channelActive}——那时它们还不在 pipeline 里。
 *       后果是真存在的：{@code AuthHandler} 的握手超时任务写在
 *       {@code channelActive} 里，不补发就永远不会启动
 *       （实测：不发 AUTH 的连接能一直挂着，{@code auth-timeout-ms} 形同虚设）。
 *       注意用 {@code ctx.fireChannelActive()}：它从<b>当前处理器的下一个</b>开始找，
 *       因此前面的 {@code ConnectionLimiter} 不会被重复触发、连接数不会被算两次。</li>
 *   <li><b>「一个字节都没收到」的时限</b>。握手超时有两个阶段：连接建立后
 *       一个字都不发（本类负责，此时协议未知，<b>不能</b>回任何字节，只能关）；
 *       已分流但迟迟不发 AUTH（{@code AuthHandler} 负责，可以回 40101）。
 *       只做后者的话，攻击者只要「连上不说话」就能无限占资源——
 *       而那正是设计里说的「用极低成本占满连接数」。</li>
 * </ol>
 */
public class ProtocolSniffer extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ProtocolSniffer.class);

    /** 安装某一种协议的处理器集合。 */
    public interface PipelineFactory {
        void build(PipelineBuilder builder);
    }

    private final PipelineFactory tcpFactory;
    private final PipelineFactory webSocketFactory;
    private final long authTimeoutMillis;

    /** 分流只做一次；此后本处理器是直通阀。 */
    private boolean decided;

    /** 「第一字节」死线。仅用于提前取消，正确性靠任务里的 {@link #decided} 判断。 */
    private ScheduledFuture<?> firstByteDeadline;

    public ProtocolSniffer(PipelineFactory tcpFactory, PipelineFactory webSocketFactory,
                           long authTimeoutMillis) {
        this.tcpFactory = tcpFactory;
        this.webSocketFactory = webSocketFactory;
        this.authTimeoutMillis = authTimeoutMillis;
    }

    /**
     * 连接建立：记下握手总死线，并给「一个字节都不发」这种情况上闹钟。
     *
     * <p>任务体里再查一次 {@link #decided}，而不是只依赖 {@code cancel}：
     * 「分流」与「定时器触发」跑在同一个 EventLoop 上，但 {@code cancel} 与
     * 任务的执行边界的先后并不由我们看到的部分决定。判在任务体内就永远不会误杀。
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ChannelAttributes.armHandshakeDeadline(ctx.channel(), authTimeoutMillis);
        firstByteDeadline = ctx.executor().schedule(() -> {
            if (decided) {
                return;
            }
            // 不回错误帧：连承载方式都还没确定（可能是 WS，也可能是原生 TCP），
            // 此时发出任何字节都可能被客户端当成合法数据。
            log.info("连接建立后 {}ms 内未收到任何字节，主动断开 remote={}",
                    authTimeoutMillis, ctx.channel().remoteAddress());
            ctx.close();
        }, authTimeoutMillis, TimeUnit.MILLISECONDS);
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        cancelFirstByteDeadline();
        ctx.fireChannelInactive();
    }

    private void cancelFirstByteDeadline() {
        if (firstByteDeadline != null) {
            firstByteDeadline.cancel(false);
            firstByteDeadline = null;
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (decided) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (!(msg instanceof ByteBuf in) || !in.isReadable()) {
            // 非字节流（理论上不会发生）或空读：交回给下游，不做任何猜测。
            ctx.fireChannelRead(msg);
            return;
        }

        int firstByte = in.getUnsignedByte(in.readerIndex());
        boolean rawTcp = firstByte == 0x00;

        PipelineBuilder builder = new PipelineBuilder(ctx.pipeline(), ctx.name());
        if (rawTcp) {
            log.debug("分流为原生 TCP（首字节 0x00） remote={}", ctx.channel().remoteAddress());
            tcpFactory.build(builder);
        } else {
            // 不能用 "0x{:02x}"：SLF4J 的占位符只有 {}，格式说明符会被原样打出来。
            log.debug("分流为 HTTP/WebSocket（首字节 {}） remote={}",
                    String.format("0x%02x", firstByte), ctx.channel().remoteAddress());
            webSocketFactory.build(builder);
        }

        decided = true;
        cancelFirstByteDeadline();
        // 顺序：先补发「连接已建立」，再转发字节。
        // 反过来的话，下游会先看到 AUTH 帧、再看到 channelActive，
        // 而 AuthHandler 正是在 channelActive 里把状态置为 AWAITING_AUTH 的。
        ctx.fireChannelActive();
        ctx.fireChannelRead(in);
    }
}
