package com.tm.im.channel.push;

import com.tm.im.channel.cluster.ActorRouteTable;
import com.tm.im.channel.cluster.NodeIdentity;
import com.tm.im.channel.cluster.NodePublisher;
import com.tm.im.channel.codec.TransportMessageMapper;
import com.tm.im.channel.registry.LocalConnectionRegistry;
import com.tm.im.channel.session.ConnectionRegistry;
import com.tm.im.channel.session.TmConnection;
import com.tm.im.channel.session.TmSession;
import com.tm.im.domain.entity.Message;
import com.tm.im.domain.enums.ActorType;
import com.tm.im.domain.enums.MessageType;
import com.tm.im.proto.transport.Frame;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 推送网关「先本节点、再跨节点」的决策验证（DESIGN §7.4）。
 *
 * <p>这里钉住的是四条只有多实例部署时才会显形的规则：
 * <ol>
 *   <li><b>本地命中就不该碰 Redis</b>：那是每一条本地消息的必经之路，
 *       多发一次 Pub/Sub 会让单机部署也背上跨节点的开销；</li>
 *   <li><b>返回 0 的两种情形必须区分</b>：没有路由（对方离线）与
 *       「路由指向本节点却没有连接」（陈旧路由）看起来都是「没投出去」，
 *       但后者若被误当成「在别的节点」就会往自己的频道投一帧，
 *       变成一次无用往返 + 一条「收到了发给自己的推送」的日志；</li>
 *   <li><b>发布失败不能抛</b>：Redis 抖动时抛异常会让发送方的整条链路失败，
 *       而消息已经落库了——它的正确结局是「等对方重连后 SYNC 补齐」；</li>
 *   <li>返回值语义（{@code ≥1} 只表示「已投出」）。</li>
 * </ol>
 *
 * <p>本类不连 Redis：总线那一侧（真正的订阅与投递）由 {@code PushBusRedisIT} 覆盖。
 */
class ClusterMessagePushPortTest {

    private static final long ACTOR = 1001L;
    private static final String ME = "node-me";
    private static final String OTHER = "node-other";

    private LocalConnectionRegistry localRegistry;
    private ConnectionRegistry registry;
    private FakeRoutes routes;
    private RecordingPublisher publisher;
    private ClusterMessagePushPort port;

    @BeforeEach
    void setUp() {
        localRegistry = new LocalConnectionRegistry();
        registry = localRegistry;
        routes = new FakeRoutes();
        publisher = new RecordingPublisher();
        port = new ClusterMessagePushPort(new LocalMessagePushPort(registry,
                new TransportMessageMapper(ZoneId.of("Asia/Shanghai"))),
                registry, routes, publisher, new NodeIdentity(ME, "host-a", 8090),
                new TransportMessageMapper(ZoneId.of("Asia/Shanghai")));
    }

    @Test
    @DisplayName("本节点有连接：直接写 Channel，不查路由、不发布")
    void localHitDoesNotTouchCluster() {
        // 真的注册一条本地连接（EmbeddedChannel），而不是伪造「命中」：
        // 「本地命中短路」是每条本地消息的必经之路，也决定了单机部署是否背上
        // 跨节点的开销——它必须用真实的注册表来验。
        EmbeddedChannel channel = new EmbeddedChannel();
        localRegistry.register(new TmConnection(channel,
                new TmSession(ACTOR, "alice", ActorType.HUMAN, "dev", "127.0.0.1:1", Instant.now())));
        // 同时把路由指向别的节点：若实现是「先查路由再写本地」，它就会走错那一条
        routes.locateResult = Optional.of(OTHER);

        assertThat(port.pushToActor(ACTOR, message())).isEqualTo(1);

        Frame written = channel.readOutbound();
        assertThat(written).as("本机直写：帧必须真的写进了 Channel").isNotNull();
        assertThat(written.getCmd()).isEqualTo(Frame.Cmd.CMD_PUSH);
        assertThat(routes.calls).as("本地已命中就不该再问路由（每一条本地消息都要问一次）").isZero();
        assertThat(publisher.published).isEmpty();
    }

    @Test
    @DisplayName("本地没命中 + 路由指向别的节点：发布过去，返回 1（已投出）")
    void remoteRouteIsPublished() {
        routes.locateResult = Optional.of(OTHER);

        assertThat(port.pushToActor(ACTOR, message())).isEqualTo(1);

        assertThat(publisher.published).hasSize(1);
        assertThat(publisher.published.get(0).nodeId).isEqualTo(OTHER);
        assertThat(publisher.published.get(0).actorId).isEqualTo(ACTOR);
        assertThat(publisher.published.get(0).frame.getCmd())
                .as("投出去的就是客户端会收到的那一帧（CMD_PUSH）")
                .isEqualTo(Frame.Cmd.CMD_PUSH);
    }

    @Test
    @DisplayName("本地没命中 + 没有路由：返回 0（对方离线，消息等 SYNC 补齐）")
    void noRouteMeansOffline() {
        routes.locateResult = Optional.empty();

        assertThat(port.pushToActor(ACTOR, message())).isZero();
        assertThat(publisher.published).isEmpty();
    }

    @Test
    @DisplayName("陈旧路由（指向本节点但本节点没有连接）：返回 0，且不往自己频道投一帧")
    void staleRouteToSelfIsNotPublished() {
        routes.locateResult = Optional.of(ME);

        assertThat(port.pushToActor(ACTOR, message())).isZero();
        assertThat(publisher.published)
                .as("往自己的频道投一帧只会得到一个「收到发给自己的推送」的日志")
                .isEmpty();
    }

    @Test
    @DisplayName("发布失败（Redis 抖动）：返回 0 而不是抛异常")
    void publishFailureIsSwallowed() {
        routes.locateResult = Optional.of(OTHER);
        publisher.fail = true;

        assertThat(port.pushToActor(ACTOR, message())).isZero();
    }

    @Test
    @DisplayName("isOnline 的语义是「值得起一次推送」，不是「一定能推达」")
    void isOnlineConsultsRouteTable() {
        assertThat(port.isOnline(ACTOR)).isFalse();

        routes.locateResult = Optional.of(OTHER);
        assertThat(port.isOnline(ACTOR)).isTrue();
        assertThat(port.localConnectionCount()).isZero();
    }

    // ------------------------------------------------------------------ 替身

    private static Message message() {
        Message message = new Message();
        message.setId(700000000000000001L);
        message.setConvId(1001L);
        message.setSeq(7L);
        message.setSenderId(2002L);
        message.setMsgType(MessageType.TEXT);
        message.setContent("{\"text\":\"hi\"}");
        message.setCreatedAt(LocalDateTime.of(2026, 1, 1, 8, 0, 0, 123_000_000));
        return message;
    }

    /** 只实现 locate 的路由表替身；另外两个方法在本测试里不该被调用。 */
    private static final class FakeRoutes implements ActorRouteTable {

        private Optional<String> locateResult = Optional.empty();
        private int calls;

        @Override
        public void bind(long actorId) {
            throw new UnsupportedOperationException("推送不该改路由");
        }

        @Override
        public boolean unbind(long actorId) {
            throw new UnsupportedOperationException("推送不该改路由");
        }

        @Override
        public Optional<String> locate(long actorId) {
            calls++;
            return locateResult;
        }
    }

    private static final class RecordingPublisher implements NodePublisher {

        private final List<Published> published = new ArrayList<>();
        private boolean fail;

        @Override
        public boolean publish(String nodeId, long actorId, Frame frame) {
            published.add(new Published(nodeId, actorId, frame));
            return !fail;
        }

        private record Published(String nodeId, long actorId, Frame frame) {
        }
    }
}
