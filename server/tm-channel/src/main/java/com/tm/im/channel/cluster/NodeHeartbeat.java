package com.tm.im.channel.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 节点心跳：启动时注册 {@code tm:node:{nodeId}}，之后按 {@code tm.node.heartbeat-seconds}
 * 续期，停机时注销（DESIGN §7.4）。
 *
 * <p>没有它，别的节点无法区分「node-7 崩了」与「node-7 只是这一分钟没有消息要推」——
 * 于是崩溃节点的 Actor 会被一直当成在线，消息被投进一个没人订阅的频道。
 *
 * <p><b>为什么是 {@link SmartLifecycle} 而不是 {@code @PostConstruct} 加一个线程</b>：
 * 启动时机必须相对 Netty 是「早」的（{@link #PHASE} &lt; {@code NettyServer} 的 1000）——
 * 端口一旦对外接受连接，就会有人鉴权、就会写路由，而此刻「本节点还活着」这件事
 * 必须先存在于 Redis 里，否则第一个连接的路由在别人眼里就是指向一个死节点。
 * 停机同理需要<b>晚</b>于 Netty：先停止收连接与投递，再宣布自己退出，
 * 中间那段窗口里仍有连接在收消息（它们是被 KICK 掉的，会立刻重连到别的节点）。
 *
 * <p><b>失败一律只告警</b>：Redis 抖动时节点注册会失败，若让它抛异常，
 * 整个网关就起不来了 —— 而这时它其实完全能服务本机连接（只是跨节点推送退化）。
 * 续期失败会顺带重新注册一次：Redis 重启过、运维清过键，都能自愈。
 */
@Component
@ConditionalOnProperty(prefix = "tm.netty", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NodeHeartbeat implements SmartLifecycle {

    /**
     * 启动顺序（数值越小越早启动、越晚停止）：
     * 500 &lt; {@code NettyServer} 的 1000，即「先登记为存活，后开始接受连接」，
     * 停止时反过来「先停止接受连接，后注销」。
     */
    static final int PHASE = 500;

    private static final Logger log = LoggerFactory.getLogger(NodeHeartbeat.class);

    private final NodeRegistry nodes;
    private final NodeIdentity self;
    private final NodeProperties properties;

    private volatile ScheduledExecutorService scheduler;
    private volatile boolean running;

    public NodeHeartbeat(NodeRegistry nodes, NodeIdentity self, NodeProperties properties) {
        this.nodes = nodes;
        this.self = self;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        NodeInfo info = new NodeInfo(self.nodeId(), self.host(), self.nettyPort(), System.currentTimeMillis());
        register(info);

        int periodSeconds = properties.getHeartbeatSeconds();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tm-node-heartbeat");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> tick(info), periodSeconds, periodSeconds, TimeUnit.SECONDS);
        log.info("节点心跳已启动 nodeId={} ttl={} 间隔={}s", self.nodeId(), properties.getTtl(), periodSeconds);
    }

    /** 一次续期；键不在了就重新注册（Redis 重启、被清理、长时间失联后都需要它）。 */
    void tick(NodeInfo info) {
        try {
            if (!nodes.renew(self.nodeId(), properties.getTtl())) {
                log.warn("tm:node:{} 已不存在（Redis 重启或被清理？），重新注册以免本节点的路由被当成死节点",
                        self.nodeId());
                register(info);
            }
        } catch (RuntimeException e) {
            log.warn("节点心跳失败 nodeId={}：本节点暂时会被其它节点视为不存活，"
                    + "跨节点推送在此期间会退化为「等对方重连后 SYNC 补齐」。cause={}",
                    self.nodeId(), e.toString());
        }
    }

    private void register(NodeInfo info) {
        try {
            nodes.register(info, properties.getTtl());
        } catch (RuntimeException e) {
            // 见类注释：宁可退化为「只有本机推送」，也不能让服务起不来。
            log.warn("节点注册失败 nodeId={}：本实例暂时不会被其它节点当作存活节点。cause={}",
                    self.nodeId(), e.toString());
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        try {
            nodes.unregister(self.nodeId());
            log.info("节点已下线 nodeId={}", self.nodeId());
        } catch (RuntimeException e) {
            // 删不掉也没关系：TTL 到了自然消失。
            log.warn("节点注销失败 nodeId={}：将由 {} 的 TTL 自然过期。cause={}",
                    self.nodeId(), properties.getTtl(), e.toString());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
