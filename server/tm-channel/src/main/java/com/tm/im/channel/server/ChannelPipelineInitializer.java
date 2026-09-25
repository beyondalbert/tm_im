package com.tm.im.channel.server;

import com.tm.im.channel.codec.TransportMessageMapper;
import com.tm.im.channel.codec.WsBinaryFrameDecoder;
import com.tm.im.channel.codec.WsBinaryFrameEncoder;
import com.tm.im.channel.config.NettyProperties;
import com.tm.im.channel.handler.AuthHandler;
import com.tm.im.channel.handler.BusinessHandler;
import com.tm.im.channel.handler.FrameRateLimiter;
import com.tm.im.channel.handler.HeartbeatHandler;
import com.tm.im.channel.session.ConnectionRegistry;
import com.tm.im.core.identity.IdentityService;
import com.tm.im.core.message.MessageCommandPort;
import com.tm.im.proto.transport.Frame;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;
import io.netty.handler.timeout.IdleStateHandler;

import java.time.ZoneId;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 每条新连接的 pipeline 装配。
 *
 * <p><b>两种承载共存于同一端口</b>（04-realtime.md §1.1）：
 * <pre>
 * 浏览器/移动端  wss://host:8090/ws    → HTTP 升级到 WebSocket（无长度前缀）
 * 原生程序       host:8090             → 4 字节大端长度前缀 + Frame
 * </pre>
 * 分流由 {@link ProtocolSniffer} 完成。为什么不做成两个端口：对外只需要暴露一个端口，
 * 运维（以及将来的 WSS 证书、SLB 配置）都少一半工作。
 *
 * <p><b>处理器添加顺序是有语义的，不能随手调</b>：
 * <ul>
 *   <li>入站事件按「添加顺序」流动，出站事件按「相反顺序」流动。
 *       所以编码器必须添加在解码器<b>之前</b>（更靠近 head），
 *       否则 Frame → ByteBuf → WS 帧 的转换链会断开。</li>
 *   <li>限流器放在解码之后、鉴权之前：鉴权要查库，未鉴权连接若不受限
 *       就是一条打数据库的免费放大器。</li>
 *   <li>心跳在鉴权之后：这样「未鉴权就发 PING」会被鉴权层按
 *       「首帧必须是 AUTH」拒绝，而不是得到 PONG 而被变相鼓励。</li>
 * </ul>
 */
public class ChannelPipelineInitializer extends ChannelInitializer<SocketChannel> {

    private static final String WS_PATH = "/ws";

    private final NettyProperties properties;
    private final IdentityService identityService;
    private final ConnectionRegistry registry;
    private final ThreadPoolExecutor businessExecutor;
    private final ConnectionLimiter limiter;
    private final MessageCommandPort messages;
    private final ZoneId databaseZone;
    private final TransportMessageMapper messageMapper;

    /**
     * @param messages     业务命令的调用面（{@code MessageService}）。
     *                     用接口而不是具体类：本类的职责是装配 pipeline，
     *                     不该被“消息服务需要七个依赖”这件事拖住——
     *                     见 {@link MessageCommandPort} 的注释。
     * @param databaseZone 回执里 {@code created_at_ms} 的换算时区（{@code tm.time.zone}）
     * @param messageMapper 续传帧里的领域消息 → 传输消息（与推送帧同一份翻译）
     */
    public ChannelPipelineInitializer(NettyProperties properties,
                                      IdentityService identityService,
                                      ConnectionRegistry registry,
                                      ThreadPoolExecutor businessExecutor,
                                      ConnectionLimiter limiter,
                                      MessageCommandPort messages,
                                      ZoneId databaseZone,
                                      TransportMessageMapper messageMapper) {
        this.properties = properties;
        this.identityService = identityService;
        this.registry = registry;
        this.businessExecutor = businessExecutor;
        this.limiter = limiter;
        this.messages = messages;
        this.databaseZone = databaseZone;
        this.messageMapper = messageMapper;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        // 第 1 个处理器：连接数上限。放在最前面，超限的连接不消耗任何后续资源。
        pipeline.addLast("conn-limit", limiter);
        // 第 2 个：分流。它读到第一个字节后，把目标协议的处理器插在自己后面。
        //     它还负责两件只有它能做的事：回放 channelActive（否则 AuthHandler 的
        //     握手超时永远不启动）、以及「连上后一个字节都不发」的兜底超时。
        pipeline.addLast("protocol-sniffer", new ProtocolSniffer(
                new TcpPipelineFactory(), new WebSocketPipelineFactory(),
                properties.getAuthTimeoutMs()));
    }

    /** 原生 TCP：4 字节大端长度前缀，无 HTTP。 */
    private final class TcpPipelineFactory implements ProtocolSniffer.PipelineFactory {
        @Override
        public void build(PipelineBuilder pipeline) {
            pipeline.add("length-decoder", new LengthFieldBasedFrameDecoder(
                    properties.getMaxFrameBytes(), 0, 4, 0, 4));
            // 添加顺序：LengthFieldPrepender 在 ProtobufEncoder 之前（更靠近 head），
            // 出站才会先由 ProtobufEncoder 产出 ByteBuf，再由 Prepender 加前缀。
            pipeline.add("length-prepender", new LengthFieldPrepender(4));
            addCommon(pipeline);
        }
    }

    /** WebSocket：HTTP 升级后按 WS 帧边界切分，无长度前缀。 */
    private final class WebSocketPipelineFactory implements ProtocolSniffer.PipelineFactory {
        @Override
        public void build(PipelineBuilder pipeline) {
            pipeline.add("http-codec", new HttpServerCodec());
            // 聚合 HTTP 分片：WebSocket 的 Upgrade 请求头部可能跨多个 TCP 段，
            // 不聚合就无法正确完成握手。
            pipeline.add("http-aggregator", new HttpObjectAggregator(64 * 1024));
            // 握手 + 关闭帧处理。第 4 个参数是单帧上限，必须与 TCP 侧口径一致，
            // 否则同一个客户端换承载方式后行为会变。
            pipeline.add("ws-protocol", new WebSocketServerProtocolHandler(
                    WS_PATH, null, true, properties.getMaxFrameBytes()));
            // WS 允许把一条消息拆成多个帧（分片）。不聚合的话，
            // 半截 protobuf 会被当成完整帧解析 —— 有时甚至解析「成功」但字段错位。
            pipeline.add("ws-aggregator", new WebSocketFrameAggregator(properties.getMaxFrameBytes()));
            // 出站路径（ByteBuf → WS 帧）必须在 ProtobufEncoder 之前添加；
            // 入站路径（WS 帧 → ByteBuf）必须在 ProtobufDecoder 之前添加 ——
            // 两者恰好都是「先添加」，因为入站按添加顺序、出站按相反顺序。
            pipeline.add("ws-frame-encoder", new WsBinaryFrameEncoder());
            pipeline.add("ws-frame-decoder", new WsBinaryFrameDecoder());
            addCommon(pipeline);
        }
    }

    /**
     * 两条路径共享的部分：protobuf 编解码、限流、空闲检测、握手/鉴权、心跳、分发。
     *
     * <p>共享是刻意的：业务语义（谁能连、怎么保活、命令怎么处理）不应因承载方式而异。
     * 04-realtime.md §7 要求两种承载语义完全一致，把公共段抽出来是最直接的保证方式。
     */
    private void addCommon(PipelineBuilder pipeline) {
        // 入站：ByteBuf → Frame。出站：Frame → ByteBuf（添加顺序见类注释）。
        pipeline.add("protobuf-encoder", new ProtobufEncoder());
        pipeline.add("protobuf-decoder", new ProtobufDecoder(Frame.getDefaultInstance()));

        pipeline.add("rate-limit", new FrameRateLimiter(properties.getMaxFramesPerSecond()));

        // readerIdle 触发服务端探活，allIdle 触发断开（DESIGN §7.2 / 04-realtime.md §5.3）。
        pipeline.add("idle", new IdleStateHandler(
                properties.getHeartbeatIdleSeconds(),
                0,
                properties.getHeartbeatTimeoutSeconds(),
                TimeUnit.SECONDS));

        pipeline.add("auth", new AuthHandler(
                identityService, registry, businessExecutor,
                properties.getAuthTimeoutMs(), properties.getHeartbeatIdleSeconds()));
        pipeline.add("heartbeat", new HeartbeatHandler());
        pipeline.add("business", new BusinessHandler(messages, businessExecutor, databaseZone, messageMapper));
    }
}
