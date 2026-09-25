package com.tm.im.channel.cluster;

import com.tm.im.channel.codec.Frames;
import com.tm.im.channel.registry.LocalConnectionRegistry;
import com.tm.im.channel.session.TmConnection;
import com.tm.im.channel.session.TmSession;
import com.tm.im.domain.enums.ActorType;
import com.tm.im.proto.transport.Frame;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 「本地注册」与「集群路由发布」之间的不变式：本地表里有这个人，路由才允许指向本节点。
 *
 * <p>三条规则都是「改坏了不影响单机自测」的：漏绑定（消息推不出去）、
 * 顶号时误删新连接的路由（该用户此后收不到跨节点推送）、
 * Redis 故障导致登录失败。此处的测试用真实 {@link LocalConnectionRegistry}
 * （顶号语义是它实现的，替身会掩盖掉真正的竞态）加路由表替身。
 */
class ClusterAwareConnectionRegistryTest {

    private static final long ALICE = 1001L;
    private static final long BOT = 2002L;

    private final LocalConnectionRegistry local = new LocalConnectionRegistry();
    private final RecordingActorRouteTable routes = new RecordingActorRouteTable("node-a");
    private final ClusterAwareConnectionRegistry registry =
            new ClusterAwareConnectionRegistry(local, routes);

    @Test
    @DisplayName("连接上线即发布路由：别的节点要能在 AUTH_OK 之前查到本节点")
    void registerPublishesRoute() {
        TmConnection connection = connection(ALICE);

        assertThat(registry.register(connection)).isZero();

        assertThat(routes.bindCalls()).containsExactly(ALICE);
        assertThat(routes.isBound(ALICE)).isTrue();
        assertThat(registry.isOnline(ALICE)).isTrue();
    }

    @Test
    @DisplayName("最后一条连接断开才释放路由")
    void lastConnectionReleasesRoute() {
        TmConnection connection = connection(ALICE);
        registry.register(connection);

        registry.unregister(connection);

        assertThat(routes.isBound(ALICE)).as("连接没了，路由必须释放").isFalse();
        assertThat(routes.unbindCalls()).containsExactly(ALICE);
    }

    @Test
    @DisplayName("重复注销是幂等的：第二次释放（CAS 落空）不能抛，也不能动到别人的路由")
    void repeatedUnregisterIsHarmless() {
        TmConnection connection = connection(ALICE);
        registry.register(connection);

        registry.unregister(connection);
        // channelInactive 可能在同一条连接上被触发多次（异常关闭、超时关闭、顶号关闭）。
        // 这里多出来的那一次 unbind 会落到 Redis 的 CAS 上（值已不是自己 → 返回 false），
        // 见 ClusterRedisIT「解绑是 CAS」那一条。要求「一次也不多发」需要额外的状态，
        // 而多发一次的代价只是一次幂等写入。
        assertThatCode(() -> registry.unregister(connection)).doesNotThrowAnyException();

        assertThat(routes.isBound(ALICE)).isFalse();
        assertThat(registry.isOnline(ALICE)).isFalse();
    }

    @Test
    @DisplayName("顶号：旧连接的清理动作不得动新连接的路由（否则该用户收不到任何跨节点推送）")
    void evictedConnectionMustNotReleaseTheNewRoute() {
        TmConnection old = connection(ALICE);
        TmConnection fresh = connection(ALICE);

        registry.register(old);
        assertThat(registry.register(fresh)).as("旧连接被顶掉").isEqualTo(1);

        registry.unregister(old);

        assertThat(local.isOnline(ALICE)).as("新连接必须还在本地表里").isTrue();
        assertThat(routes.isBound(ALICE))
                .as("路由必须仍指向本节点：删掉它等于「他明明在线，却收不到推送」")
                .isTrue();
        assertThat(routes.unbindCalls()).isEmpty();
    }

    @Test
    @DisplayName("Redis 故障只降级、不影响登录：路由发布失败后 AUTH 仍应成功")
    void routePublishFailureDoesNotBreakLogin() {
        routes.failBindAlways();

        assertThatCode(() -> registry.register(connection(ALICE))).doesNotThrowAnyException();
        assertThat(registry.isOnline(ALICE))
                .as("本地连接必须正常建立：跨节点推送退化，本机推送与重连后的 SYNC 补齐不受影响")
                .isTrue();
    }

    @Test
    @DisplayName("Redis 故障时释放路由失败也不能抛：断开连接是最频繁的路径，抛出去会污染调用的收尾逻辑")
    void routeReleaseFailureDoesNotBreakLogout() {
        TmConnection connection = connection(ALICE);
        registry.register(connection);
        routes.failUnbindAlways();

        assertThatCode(() -> registry.unregister(connection)).doesNotThrowAnyException();
        assertThat(registry.isOnline(ALICE)).isFalse();
    }

    @Test
    @DisplayName("其余能力原样委托：推送、广播、计数在装饰后行为不变")
    void delegatesEverythingElse() {
        EmbeddedChannel channel = new EmbeddedChannel();
        registry.register(new TmConnection(channel, session(ALICE)));

        Frame frame = Frames.of(Frame.Cmd.CMD_PING, 7, null);
        assertThat(registry.push(ALICE, frame)).isEqualTo(1);
        assertThat(channel.<Object>readOutbound()).isSameAs(frame);

        assertThat(registry.push(BOT, frame)).as("不在本节点 → 0").isZero();
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.connections()).hasSize(1);
        assertThat(registry.droppedFrames()).isZero();

        registry.broadcast(Frames.of(Frame.Cmd.CMD_PING, 8, null));
        assertThat(channel.<Object>readOutbound()).isNotNull();
    }

    private static TmConnection connection(long actorId) {
        return new TmConnection(new EmbeddedChannel(), session(actorId));
    }

    private static TmSession session(long actorId) {
        return new TmSession(actorId, "h" + actorId, ActorType.HUMAN, "device-1",
                "127.0.0.1:12345", Instant.now());
    }
}
