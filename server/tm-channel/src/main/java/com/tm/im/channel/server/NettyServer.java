package com.tm.im.channel.server;

import com.tm.im.channel.codec.Frames;
import com.tm.im.channel.codec.TransportMessageMapper;
import com.tm.im.channel.config.ChannelConfiguration;
import com.tm.im.channel.config.NettyProperties;
import com.tm.im.channel.session.ConnectionRegistry;
import com.tm.im.channel.session.TmConnection;
import com.tm.im.core.identity.IdentityService;
import com.tm.im.core.message.MessageCommandPort;
import com.tm.im.proto.transport.KickReason;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Netty 长连接服务，由 Spring 管理启停（DESIGN §7.1）。
 *
 * <p>用 {@link SmartLifecycle} 而不是 {@code @PostConstruct}：
 * 启动要发生在<b>整个上下文就绪之后</b>——否则可能出现「端口已经对外收连接，
 * 但后面某个 Bean 还没初始化好」，客户端连上来立刻被拒。
 * 停机同理需要排在依赖方之后（{@link #getPhase()} 给一个靠后的值）。
 *
 * <p><b>线程组规划</b>（DESIGN §7.1）：
 * <pre>
 * boss     1 个线程，只做 accept
 * worker   CPU×2，只做编解码（绝不在上面跑 DB）
 * business 独立线程池，业务处理（见 ChannelConfiguration）
 * </pre>
 */
@Component
@ConditionalOnProperty(prefix = "tm.netty", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NettyServer implements SmartLifecycle {

    /** 长度前缀首字节为 0x00 的充要条件（见 ProtocolSniffer）。 */
    static final int SNIFFER_LIMIT_BYTES = 1 << 24;

    private static final Logger log = LoggerFactory.getLogger(NettyServer.class);

    private final NettyProperties properties;
    private final IdentityService identityService;
    private final ConnectionRegistry registry;
    private final ThreadPoolExecutor businessExecutor;
    private final MessageCommandPort messages;
    private final ZoneId databaseZone;
    private final TransportMessageMapper messageMapper;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private ConnectionLimiter connectionLimiter;
    private volatile boolean running;

    public NettyServer(NettyProperties properties,
                       IdentityService identityService,
                       ConnectionRegistry registry,
                       ThreadPoolExecutor nettyBusinessExecutor,
                       MessageCommandPort messages,
                       ZoneId databaseZone,
                       TransportMessageMapper messageMapper) {
        this.properties = properties;
        this.identityService = identityService;
        this.registry = registry;
        this.businessExecutor = nettyBusinessExecutor;
        this.messages = messages;
        this.databaseZone = databaseZone;
        this.messageMapper = messageMapper;
    }

    @Override
    public void start() {
        validate();
        bossGroup = new NioEventLoopGroup(properties.getBossThreads(),
                ChannelConfiguration.namedFactory("tm-boss-"));
        workerGroup = new NioEventLoopGroup(properties.resolvedWorkerThreads(),
                ChannelConfiguration.namedFactory("tm-worker-"));

        connectionLimiter = new ConnectionLimiter(properties.getMaxConnections());
        ChannelPipelineInitializer initializer = new ChannelPipelineInitializer(
                properties, identityService, registry, businessExecutor, connectionLimiter,
                messages, databaseZone, messageMapper);

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                // SO_BACKLOG：accept 队列长度。太小会在突发连接时丢握手（客户端表现为 connect timeout）。
                .option(ChannelOption.SO_BACKLOG, 1024)
                // SO_REUSEADDR：避免「刚重启时端口还在 TIME_WAIT」导致绑定失败。
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                // TCP_NODELAY：IM 帧都很小，Nagle 算法会把它们攒起来再发，
                // 表现是「消息偶尔延迟 40ms」这类难以复现的抖动。
                .childOption(ChannelOption.TCP_NODELAY, true)
                // 写缓冲水位 = 背压开关（DESIGN §7.6）：超过高水位后 isWritable() 变 false。
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(
                        properties.getWriteBufferLowWaterMark(),
                        properties.getWriteBufferHighWaterMark()))
                .childHandler(initializer);

        try {
            serverChannel = bootstrap.bind(properties.getPort()).sync().channel();
            running = true;
            log.info("Netty 长连接已启动 port={}（WebSocket {} 与原生 TCP 4 字节大端长度前缀共用此端口）"
                            + " boss={} worker={} business={} maxConnections={}",
                    port(), "/ws", properties.getBossThreads(), properties.resolvedWorkerThreads(),
                    properties.getBusinessThreads(), properties.getMaxConnections());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdownGroups();
            throw new IllegalStateException("绑定端口 " + properties.getPort() + " 时被中断", e);
        } catch (RuntimeException e) {
            // 端口被占用是最常见的原因。这里补一句提示，避免只看到
            // "Address already in use" 却要自己去猜是不是上一个实例没退干净。
            shutdownGroups();
            throw new IllegalStateException(
                    "绑定端口 " + properties.getPort() + " 失败（是否已有实例在运行？）", e);
        }
    }

    /**
     * 启动前的自检。
     *
     * <p>{@code max-frame-bytes < 16MB} 是 {@code ProtocolSniffer} 分流判据成立的前提。
     * 把它做成启动失败而不是注释，是因为一旦越界，症状是「某些较大的帧被当成 HTTP
     * 解析失败」——从错误信息完全看不出与这个配置项有关。
     */
    private void validate() {
        int maxFrame = properties.getMaxFrameBytes();
        if (maxFrame <= 0 || maxFrame >= SNIFFER_LIMIT_BYTES) {
            throw new IllegalStateException(
                    "tm.netty.max-frame-bytes 必须落在 (0, " + SNIFFER_LIMIT_BYTES + ") 内，实际 " + maxFrame
                            + "：达到 16MB 后长度前缀的首字节不再是 0x00，同端口协议分流判据失效");
        }
        if (properties.getWriteBufferLowWaterMark() >= properties.getWriteBufferHighWaterMark()) {
            throw new IllegalStateException(
                    "写缓冲低水位必须小于高水位，实际 low=" + properties.getWriteBufferLowWaterMark()
                            + " high=" + properties.getWriteBufferHighWaterMark()
                            + "：相等时 Netty 会在同一水位反复翻转，背压判定失去意义");
        }
        // auth-timeout-ms = 0 必须拒绝，而不是「解释成某种合理行为」：
        // 它既可以读作「不超时」（于是未鉴权连接永久占用资源），
        // 也可以读作「立刻超时」（于是所有连接一连上就被断开）。
        // 两种解释都是缺陷，因此在这里直接启动失败。
        if (properties.getAuthTimeoutMs() <= 0) {
            throw new IllegalStateException(
                    "tm.netty.auth-timeout-ms 必须 > 0，实际 " + properties.getAuthTimeoutMs());
        }
    }

    /**
     * 优雅停机（DESIGN §7.7）：
     * <pre>
     * 1. 停止接受新连接
     * 2. 向所有在线客户端发 KICK(SERVER_RESTART)
     * 3. 等待写缓冲刷出（上限 gracefulShutdownMillis）
     * 4. 关闭 Channel，释放线程组
     * </pre>
     *
     * <p>第 2 步不可省：客户端收到 KICK 才知道「这是服务端主动重启，
     * 应当按 retry_after_ms 退避重连」，否则它会把这当成网络故障，
     * 立刻以更激进的频率重连，重启期间反而被打满。
     *
     * <p>第 3 步有上限：停机不能无限等一个慢客户端，等不到就断开，
     * 客户端重连后按 last_seq 走 SYNC 补齐 —— 「可能丢推送但绝不丢消息」
     * 是这套设计的基本盘。
     */
    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        long deadline = System.currentTimeMillis() + properties.getGracefulShutdownMillis();

        if (serverChannel != null) {
            serverChannel.close();
            log.info("Netty 已停止接受新连接");
        }

        List<TmConnection> online = List.copyOf(registry.connections());
        if (!online.isEmpty()) {
            registry.broadcast(Frames.kick(KickReason.KICK_REASON_SERVER_RESTART,
                    "server restarting, please reconnect", properties.getGracefulShutdownMillis()));
            log.info("已向 {} 个连接发送 KICK(SERVER_RESTART)", online.size());
        }

        // 等写缓冲刷完：逐个查 pending 写是否已排空。
        while (System.currentTimeMillis() < deadline && !online.isEmpty()
                && online.stream().anyMatch(c -> c.channel().isActive() && !c.channel().isWritable())) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        for (TmConnection connection : online) {
            // close() 是异步的；这里不等，因为紧接着就会关闭整个 EventLoopGroup。
            connection.channel().close();
        }
        registry.connections().forEach(c -> c.channel().close());

        shutdownGroups();
        log.info("Netty 已停机");
    }

    private void shutdownGroups() {
        // quietPeriod=0：不再等待新任务；timeout：给在途的写操作一点时间。
        if (workerGroup != null) {
            Future<?> f = workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS);
            f.awaitUninterruptibly(3, TimeUnit.SECONDS);
        }
        if (bossGroup != null) {
            Future<?> f = bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS);
            f.awaitUninterruptibly(3, TimeUnit.SECONDS);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 启动顺序：数值越大越晚启动、越早停止。
     * 给一个靠后的值，确保 Netty 在依赖它的 Bean 之后才停。
     */
    @Override
    public int getPhase() {
        return 1000;
    }

    /** 实际绑定端口（配置为 0 时由系统分配，测试依赖这个值）。 */
    public int port() {
        if (serverChannel == null) {
            return -1;
        }
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    /** 当前打开的连接数（含未鉴权的），供运维端点使用。 */
    public int openConnections() {
        return connectionLimiter == null ? 0 : connectionLimiter.openConnections();
    }

    public long rejectedConnections() {
        return connectionLimiter == null ? 0 : connectionLimiter.rejectedConnections();
    }
}
