package com.tm.im.channel.server;

import com.tm.im.channel.codec.Frames;
import com.tm.im.channel.session.ChannelAttributes;
import com.tm.im.proto.transport.AuthRequest;
import com.tm.im.proto.transport.Frame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同端口协议分流。
 *
 * <p>最容易写错、也最难发现的不是「怎么判断首字节」，而是
 * <b>判断完之后已经读进来的那些字节去哪了</b>：分流器为了判断协议必然要先看一眼
 * 缓冲区，但那次 TCP 读取里通常还有同一帧的其余字节。若这些字节没被完整交给
 * 新安装的处理器，症状是「每个连接的第一条消息都丢，第二条起一切正常」——
 * 会被当成客户端 bug 查很久。
 *
 * <p>另一半是本类作为「每连接最早经过的位置」所承担的两件事：
 * 给动态安装的处理器<b>回放 channelActive</b>（否则 {@code AuthHandler} 的握手超时
 * 任务永远不会启动 —— 这是真实连接测试抓到的缺陷），以及
 * <b>「一个字节都没收到」的兜底超时</b>（否则只连不发的连接可以无限占用资源）。
 */
class ProtocolSnifferTest {

    private static final long TIMEOUT_MS = 500;

    /** 记录「装上了哪个协议」、「后续收到了哪些字节」以及「收到过几次 channelActive」。 */
    private static final class Spy extends ChannelInboundHandlerAdapter {
        final List<ByteBuf> received = new ArrayList<>();
        int activeEvents;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            activeEvents++;
            ctx.fireChannelActive();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ByteBuf buf) {
                received.add(buf.retain());
            }
            ctx.fireChannelRead(msg);
        }
    }

    /** 分流器装在「注册之前」，这样 channelActive 会真的经过它（与生产一致）。 */
    private static EmbeddedChannel channelWithSniffer(Spy tcp, Spy ws, long timeoutMs) {
        return new EmbeddedChannel(new ProtocolSniffer(
                builder -> builder.add("tcp-marker", tcp),
                builder -> builder.add("ws-marker", ws),
                timeoutMs));
    }

    /**
     * 同上，但把 Netty 的时钟<b>冻结</b>，从而能用 {@code advanceTimeBy} 确定性地
     * 测试定时器（不冻结就只能 sleep 真时间）。
     *
     * <p>顺序很关键，试出来的两条经验：
     * <ol>
     *   <li>先 freeze 再让分流器看到 channelActive。没冻结时调度的任务，
     *       其到期时刻是用真实时钟算的；之后再 freeze、再 advance，任务永远不会到期
     *       （两个时钟的基准差着几十万秒）。</li>
     *   <li>因此不能直接 {@code new EmbeddedChannel(handler)}（那样 channelActive
     *       在构造期间就发完了，来不及 freeze），而是先建空 channel、再 freeze、
     *       手动装上处理器、最后手动补发一次 channelActive —— 而那次「补发」
     *       恰好就是生产里真实发生的事（连接建立）。</li>
     * </ol>
     *
     * <p>推进时间的完整写法是「两步」，少一步就 silently 什么都测不到：
     * <pre>
     * channel.advanceTimeBy(500, MILLISECONDS);
     * channel.runScheduledPendingTasks();   // advanceTimeBy 只推时钟，不跑任务
     * </pre>
     */
    private static EmbeddedChannel frozenChannelWithSniffer(Spy tcp, Spy ws, long timeoutMs) {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.freezeTime();
        channel.pipeline().addLast(new ProtocolSniffer(
                builder -> builder.add("tcp-marker", tcp),
                builder -> builder.add("ws-marker", ws),
                timeoutMs));
        channel.pipeline().fireChannelActive();
        return channel;
    }

    private static Spy[] build(EmbeddedChannel channel) {
        Spy tcp = new Spy();
        Spy ws = new Spy();
        channel.pipeline().addLast("sniffer", new ProtocolSniffer(
                builder -> builder.add("tcp-marker", tcp),
                builder -> builder.add("ws-marker", ws),
                TIMEOUT_MS));
        return new Spy[]{tcp, ws};
    }

    @Test
    @DisplayName("首字节 0x00 → 原生 TCP 路径，且整段字节一个不少地转交")
    void zeroFirstByteSelectsRawTcp() {
        EmbeddedChannel channel = new EmbeddedChannel();
        Spy[] spies = build(channel);
        byte[] bytes = {0x00, 0x00, 0x00, 0x05, 1, 2, 3, 4, 5};

        channel.writeInbound(Unpooled.wrappedBuffer(bytes));

        assertThat(channel.pipeline().get("tcp-marker")).isNotNull();
        assertThat(channel.pipeline().get("ws-marker")).isNull();
        assertThat(spies[0].received).hasSize(1);
        assertThat(spies[1].received).isEmpty();
        assertThat(readBytes(spies[0].received.get(0))).isEqualTo(bytes);
    }

    @Test
    @DisplayName("首字节 'G'（GET /ws）→ HTTP/WebSocket 路径，首字节也在内")
    void httpFirstByteSelectsWebSocket() {
        EmbeddedChannel channel = new EmbeddedChannel();
        Spy[] spies = build(channel);
        byte[] request = "GET /ws HTTP/1.1\r\nHost: x\r\n\r\n".getBytes();

        channel.writeInbound(Unpooled.wrappedBuffer(request));

        assertThat(channel.pipeline().get("ws-marker")).isNotNull();
        assertThat(channel.pipeline().get("tcp-marker")).isNull();
        assertThat(spies[1].received).hasSize(1);
        byte[] forwarded = readBytes(spies[1].received.get(0));
        assertThat(forwarded).isEqualTo(request);
        assertThat(forwarded[0]).isEqualTo((byte) 'G');
    }

    @Test
    @DisplayName("真实场景：一整个带长度前缀的帧在一次读取里到达，分流后仍能解析出 Frame")
    void completeFrameSurvivesSniffing() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("sniffer", new ProtocolSniffer(
                builder -> {
                    // 与 ChannelPipelineInitializer 的 TCP 路径保持一致
                    builder.add("length-decoder", new LengthFieldBasedFrameDecoder(
                            1024 * 1024, 0, 4, 0, 4));
                    builder.add("protobuf-encoder", new ProtobufEncoder());
                    builder.add("protobuf-decoder", new ProtobufDecoder(Frame.getDefaultInstance()));
                },
                builder -> builder.add("ws-marker", new Spy()),
                TIMEOUT_MS));

        Frame auth = Frames.of(Frame.Cmd.CMD_AUTH, 1, AuthRequest.newBuilder()
                .setToken("sk_live_9f2c1d7a4b8e3f60")
                .setDeviceId("web-chrome-131")
                .build());
        byte[] frameBytes = auth.toByteArray();
        ByteBuf onWire = Unpooled.buffer();
        onWire.writeInt(frameBytes.length);
        onWire.writeBytes(frameBytes);

        channel.writeInbound(onWire);

        Frame decoded = channel.readInbound();
        assertThat(decoded).isNotNull();
        assertThat(Frames.body(decoded, AuthRequest.getDefaultInstance()).getToken())
                .isEqualTo("sk_live_9f2c1d7a4b8e3f60");
    }

    @Test
    @DisplayName("分流只做一次：后续数据不再重新判断（否则第二条消息会按错误的协议处理）")
    void decisionHappensOnlyOnce() {
        EmbeddedChannel channel = new EmbeddedChannel();
        Spy[] spies = build(channel);

        channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{0x00, 0x00, 0x00, 0x01, 0x07}));
        // 第二次写入的首字节是 'G'（0x47），若被重新判断就会切到 WS 路径
        channel.writeInbound(Unpooled.wrappedBuffer("GET".getBytes()));

        assertThat(channel.pipeline().get("ws-marker")).isNull();
        assertThat(spies[0].received).hasSize(2);
        assertThat(channel.pipeline().get("tcp-marker")).isNotNull();
    }

    @Test
    @DisplayName("首字节分两次到达（TCP 常见）：只凭第一字节即可定性，且不丢字节")
    void singleByteArrivalIsEnough() {
        EmbeddedChannel channel = new EmbeddedChannel();
        Spy[] spies = build(channel);

        channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{0x47}));
        channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{0x45, 0x54}));

        assertThat(spies[1].received).hasSize(2);
        assertThat(readBytes(spies[1].received.get(0))).isEqualTo(new byte[]{0x47});
        assertThat(readBytes(spies[1].received.get(1))).isEqualTo(new byte[]{0x45, 0x54});
    }

    @Test
    @DisplayName("动态安装的处理器必须收到 channelActive 回放（否则握手超时任务永不启动）")
    void installedHandlersReceiveChannelActiveReplay() {
        Spy tcp = new Spy();
        Spy ws = new Spy();
        // 处理器在「注册之前」加进去 → channelActive 会经过分流器（与生产路径一致）
        EmbeddedChannel channel = channelWithSniffer(tcp, ws, TIMEOUT_MS);

        // 连接建立时，目标协议的处理器还不存在，因此它们一个事件都收不到
        assertThat(tcp.activeEvents).isZero();
        assertThat(ws.activeEvents).isZero();

        channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{0x00, 0x00, 0x00, 0x01, 0x07}));

        // 分流之后必须补发，且只能补一次（补两次会让下游的连接级初始化做两遍）
        assertThat(tcp.activeEvents).isEqualTo(1);
        assertThat(ws.activeEvents).isZero();
    }

    @Test
    @DisplayName("连接建立时记下握手死线：后续 AuthHandler 用它算剩余预算，而不是重新计时")
    void handshakeDeadlineIsArmedOnConnect() {
        Spy tcp = new Spy();
        EmbeddedChannel channel = channelWithSniffer(tcp, new Spy(), TIMEOUT_MS);

        // 注意：不能靠 advanceTimeBy 验证这个属性 —— 死线用的是真实
        // System.nanoTime()，而 EmbeddedEventLoop 推进的是它自己的虚拟时钟。
        Long deadline = channel.attr(ChannelAttributes.HANDSHAKE_DEADLINE).get();
        assertThat(deadline).as("连接建立时必须记下死线（AuthHandler 靠它算剩余预算）").isNotNull();
        assertThat(deadline - System.nanoTime())
                .as("剩余量应接近完整预算")
                .isGreaterThan(TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS - 50))
                .isLessThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS));
    }

    @Test
    @DisplayName("一个字节都不发：到点主动断开（只连不发是最廉价的一种占资源方式）")
    void silentConnectionIsClosedAtDeadline() {
        Spy tcp = new Spy();
        EmbeddedChannel channel = frozenChannelWithSniffer(tcp, new Spy(), TIMEOUT_MS);

        // advanceTimeBy 只推时钟，不会跑任务；必须再显式 runScheduledPendingTasks()。
        // 少了这一步的话，测试会「成功地」证明不了任何事（连接永远开着）。
        channel.advanceTimeBy(TIMEOUT_MS - 1, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertThat(channel.isOpen()).as("未到点前不能断开").isTrue();

        channel.advanceTimeBy(2, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertThat(channel.isOpen()).as("到点必须断开").isFalse();
    }

    @Test
    @DisplayName("已分流但没完成 AUTH：不能再用「首字节超时」把人误杀")
    void decidedConnectionSurvivesFirstByteDeadline() {
        Spy tcp = new Spy();
        EmbeddedChannel channel = frozenChannelWithSniffer(tcp, new Spy(), TIMEOUT_MS);

        // 只发一个字节 0x00：分流完成，但 AUTH 帧还没来
        channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{0x00}));
        channel.advanceTimeBy(TIMEOUT_MS * 10, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();

        assertThat(channel.isOpen())
                .as("此后由 AuthHandler 按握手死线负责，分流器不应再插手")
                .isTrue();
    }

    private static byte[] readBytes(ByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), out);
        buf.release();
        return out;
    }
}
