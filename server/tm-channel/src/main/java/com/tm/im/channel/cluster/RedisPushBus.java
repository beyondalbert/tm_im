package com.tm.im.channel.cluster;

import com.tm.im.proto.transport.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 跨节点投递总线（DESIGN §7.4 的 {@code tm:push:{nodeId}}，Redis Pub/Sub）。
 *
 * <pre>
 *   1. 节点 A 处理发送，目标 Actor 在节点 B
 *   2. A 查 Redis 路由得 nodeId = B
 *   3. A 向频道 tm:push:B 发一帧（{@link PushEnvelope}）
 *   4. 只有 B 订阅该频道 → 收到后交给本地写入器 → 写 Channel
 * </pre>
 *
 * <h2>它负责什么、不负责什么</h2>
 *
 * <p><b>不负责「可靠」</b>：发布成功只表示「Redis 收下了」。B 不在线、B 刚崩、
 * B 还没订阅上，这一帧就没了——而这不是缺陷，是设计（见 {@code ClusterKeys.push}）。
 * 消息已经落库，对方重连后按 {@code last_seq} 走 SYNC 补齐。
 * 真正会把「可丢」变成「必须不丢」的是 <b>ACK</b>（发送方的 SEND_ACK 走的是
 * 另一条路：它由处理发送的那个节点直接回给发送方的连接），以及 SYNC。
 *
 * <p><b>为什么不重试</b>：重试要回答「重试多久、多少次、对方回来了要不要补投」，
 * 而这三个问题的正确答案都是「等对方重连后走 SYNC」——重试只是把同一份数据
 * 在内存里多放一会儿，还要额外处理「重试期间对方换了节点」。
 *
 * <h2>两个实现细节</h2>
 *
 * <p><b>启动相位必须早于 Netty（500 &lt; 1000）</b>：本节点一旦注册存活（{@code NodeHeartbeat}
 * 也是 500），别的节点就可能开始向本频道投递。若订阅还没建立，那一小段窗口里的推送
 * 会全部丢失（虽然后果只是「晚一点通过 SYNC 补齐」）。同相位内的顺序由 Bean 的声明顺序决定，
 * 而两者都在 500 —— 心跳在前、总线在后，恰好是想要的顺序（先能收，再宣布自己活着是更安全的，
 * 但心跳还需要 Netty 端口确定，两害相权取「同相位、声明先后」）。
 *
 * <p><b>收不到的消息只计数、不抛异常</b>：频道上可能有别的版本（滚动升级期间新旧节点并存）
 * 或人手写进去的脏数据。一条读不懂的消息让监听线程死掉，后果是本节点此后<b>再也收不到
 * 任何跨节点推送</b>——一个节点级的功能静默消失。
 */
public class RedisPushBus implements SmartLifecycle, NodePublisher {

    /**
     * 启动相位：<b>400</b>，比 {@code NodeHeartbeat} 的 500 更早，也远早于
     * {@code NettyServer} 的 1000。顺序是刻意的：
     * <pre>
     *   400 订阅本节点频道      ← 「我能收」
     *   500 向 Redis 宣布存活    ← 「别人可以向我投」（这一步之后才可能真有帧投过来）
     *  1000 接受客户端连接      ← 「有连接可投」
     * </pre>
     * 反过来的话，从「宣布存活」到「订阅完成」之间投过来的帧会全部丢失。
     * 丢掉一帧的后果只是「晚一点通过 SYNC 补齐」，但它完全没必要发生 ——
     * 而这类丢失在日志里不可见（发布方报成功、接收方什么也没收到）。
     */
    static final int PHASE = 400;

    private static final Logger log = LoggerFactory.getLogger(RedisPushBus.class);

    /**
     * 本节点收到帧之后去哪儿。单独抽成一个函数式接口而不是直接用
     * {@code ConnectionRegistry}：总线只关心「把 (actorId, 帧) 交给本地写入器」，
     * 而注册表还带着「谁在线」「有几条连接」等一堆它用不到的概念——
     * 靠一个只有一行的方法依赖进去，测试也就不必为了它去伪造一个 Netty Channel。
     */
    @FunctionalInterface
    public interface FrameSink {
        /** @return 实际写入的连接数（0 = 本节点已无该 Actor 的连接） */
        int deliver(long actorId, Frame frame);
    }

    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redis;
    private final NodeIdentity self;
    private final FrameSink sink;

    private final AtomicLong published = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong unreadable = new AtomicLong();
    private final AtomicLong missedLocally = new AtomicLong();

    private volatile RedisMessageListenerContainer container;
    private volatile boolean running;

    public RedisPushBus(RedisConnectionFactory connectionFactory, StringRedisTemplate redis,
                        NodeIdentity self, FrameSink sink) {
        this.connectionFactory = connectionFactory;
        this.redis = redis;
        this.self = self;
        this.sink = sink;
    }

    /** 订阅本节点自己的频道。幂等：重复调用不会建出第二个容器。 */
    @Override
    public synchronized void start() {
        if (container != null) {
            return;
        }
        RedisMessageListenerContainer created = new RedisMessageListenerContainer();
        created.setConnectionFactory(connectionFactory);
        created.addMessageListener((MessageListener) (message, pattern) -> onMessage(message.getBody()),
                new ChannelTopic(ClusterKeys.push(self.nodeId())));
        // 不用 @Bean 让容器自己起：它的相位是「最后启动、最先停止」
        // （见 Spring Data Redis 的 getPhase），那样在 Netty 已经接受连接之后才订阅上，
        // 中间那一段窗口里的跨节点推送全部丢失。
        created.afterPropertiesSet();
        created.start();
        container = created;
        running = true;
        log.info("跨节点推送已订阅 nodeId={} channel={}", self.nodeId(), ClusterKeys.push(self.nodeId()));
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /**
     * 把一帧投给指定节点。
     *
     * @return 是否成功发布给 Redis。false 只是「这次没投出去」——消息在库里，
     *         对方重连后走 SYNC；调用方不需要（也不应该）因此重试整条消息
     */
    @Override
    public boolean publish(String nodeId, long actorId, Frame frame) {
        try {
            redis.convertAndSend(ClusterKeys.push(nodeId),
                    PushEnvelope.of(actorId, self.nodeId(), frame).toJson());
            published.incrementAndGet();
            return true;
        } catch (RuntimeException e) {
            log.warn("跨节点推送发布失败 target={} actorId={}：该消息只能等对方重连后 SYNC 补齐。cause={}",
                    nodeId, actorId, e.toString());
            return false;
        }
    }

    /** 本节点收到了别人投来的一帧。 */
    void onMessage(byte[] body) {
        String payload = body == null ? null : new String(body, StandardCharsets.UTF_8);
        Optional<PushEnvelope> envelope = PushEnvelope.parse(payload);
        if (envelope.isEmpty()) {
            unreadable.incrementAndGet();
            log.warn("跨节点推送载荷无法解析（版本不一致或脏数据？），已跳过。payload={}",
                    payload == null ? "<null>" : payload.substring(0, Math.min(120, payload.length())));
            return;
        }
        Optional<Frame> frame = envelope.get().frameBytes();
        if (frame.isEmpty()) {
            unreadable.incrementAndGet();
            log.warn("跨节点推送的帧无法解析 actorId={} from={}",
                    envelope.get().actorId(), envelope.get().from());
            return;
        }
        int written = sink.deliver(envelope.get().actorId(), frame.get());
        if (written == 0) {
            // 收到帧但本节点已无该连接：对方刚刚断线/换到别的节点。
            // 这是正常的竞态，不是错误——消息在库里，他重连后会 SYNC 补齐。
            missedLocally.incrementAndGet();
            log.debug("跨节点推送到达时本节点已无该连接 actorId={} from={}",
                    envelope.get().actorId(), envelope.get().from());
            return;
        }
        delivered.addAndGet(written);
    }

    /** 运维/压测用的计数：发布、投达、读不懂、到达时已无连接。 */
    public long publishedCount() {
        return published.get();
    }

    public long deliveredCount() {
        return delivered.get();
    }

    public long unreadableCount() {
        return unreadable.get();
    }

    public long missedLocallyCount() {
        return missedLocally.get();
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (container != null) {
            // stop() 之后必须 destroy()：否则那个容器持有的连接不会被释放。
            // 它在 DisposableBean 上声明为 throws Exception，而停机阶段无処可抛，
            // 所以只记日志——「停机时清理不彻底」不该把整个关闭流程打断。
            try {
                container.stop();
                container.destroy();
            } catch (Exception e) {
                log.warn("跨节点推送总线停止时出错 nodeId={}", self.nodeId(), e);
            }
            container = null;
            log.info("跨节点推送已停止 nodeId={}", self.nodeId());
        }
    }
}
