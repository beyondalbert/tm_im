package com.tm.im.channel.cluster;

import com.tm.im.channel.codec.Frames;
import com.tm.im.proto.transport.Frame;
import com.tm.im.proto.transport.PushMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 跨节点投递的<b>真实 Redis</b> 验证（DESIGN §7.4 的 {@code tm:push:{nodeId}}）。
 *
 * <p>两个节点用两个 {@link RedisPushBus} 实例表示（同进程、不同 nodeId、各自的订阅）：
 * 这样测的仍是真实的「订阅自己的频道、发布到别人的频道、只有目标收到」这条路径，
 * 而单测替身<b>证明不了</b>它——替身里的「只有目标收到」是我自己写的。
 *
 * <p>三件事只有真 Redis 才验得了：
 * <ol>
 *   <li>频道名（{@code tm:push:{nodeId}}）真的是跨进程契约：测试里直接写字面量；</li>
 *   <li>订阅真的生效（Spring 容器的相位选择错了会让它「启动了但没收到」）；</li>
 *   <li>收到的是<b>逐字节相同</b>的那一帧（中间过一次 base64 + JSON）。</li>
 * </ol>
 *
 * <p>清理：不 FLUSHDB（这是共用 Redis）；每个节点停掉自己的容器即可 —— Pub/Sub 不落盘，
 * 没有需要清理的键。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ClusterItConfig.class)
class PushBusRedisIT {

    /** 本次运行的唯一后缀，节点间与其它测试运行天然不重叠。 */
    private static final String RUN = Long.toString(System.nanoTime(), 36);

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Autowired
    private StringRedisTemplate redis;

    private final List<RedisPushBus> started = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopBuses() {
        for (RedisPushBus bus : started) {
            bus.stop();
        }
        started.clear();
    }

    private RedisPushBus node(String suffix, RedisPushBus.FrameSink sink) {
        RedisPushBus bus = new RedisPushBus(connectionFactory, redis,
                new NodeIdentity("it-push-" + suffix + "-" + RUN, "host", 8090), sink);
        bus.start();
        started.add(bus);
        return bus;
    }

    @Test
    @DisplayName("节点 A 投给节点 B：只有 B 收到，且帧逐字节相同")
    void onlyTheTargetNodeReceives() {
        List<Frame> atB = new CopyOnWriteArrayList<>();
        List<Frame> atA = new CopyOnWriteArrayList<>();
        RedisPushBus busA = node("a", (actorId, frame) -> {
            atA.add(frame);
            return 1;
        });
        RedisPushBus busB = node("b", (actorId, frame) -> {
            atB.add(frame);
            return 1;
        });

        Frame frame = Frames.of(Frame.Cmd.CMD_PUSH, 0, PushMessage.newBuilder().build());
        assertThat(busA.publish("it-push-b-" + RUN, 1001L, frame)).isTrue();

        await().atMost(5, TimeUnit.SECONDS).until(() -> !atB.isEmpty());

        assertThat(atB).hasSize(1);
        assertThat(atB.get(0).toByteArray())
                .as("接收方写进 Channel 的必须是发送方编出来的那一帧")
                .isEqualTo(frame.toByteArray());
        assertThat(atA).as("订阅是按节点分的：自己的频道不该收到自己发出的帧").isEmpty();
        assertThat(busA.publishedCount()).isEqualTo(1);
        assertThat(busB.deliveredCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("目标节点不在线：发布照样成功（fire-and-forget），消息靠 SYNC 补齐")
    void publishToAbsentNodeStillSucceeds() {
        RedisPushBus busA = node("solo", (actorId, frame) -> 1);

        assertThat(busA.publish("it-push-nobody-" + RUN, 2002L,
                Frames.of(Frame.Cmd.CMD_PUSH, 0, PushMessage.newBuilder().build())))
                .as("Pub/Sub 没有「接收方在不在」的概念 —— 这正是它可用的原因")
                .isTrue();
        assertThat(busA.publishedCount()).isEqualTo(1);
        assertThat(busA.deliveredCount()).as("没人收到").isZero();
    }

    @Test
    @DisplayName("收到帧但本节点已无该连接：计入 missedLocally，不算投达")
    void frameArrivingWithNoLocalConnectionIsCounted() {
        RedisPushBus busA = node("x", (actorId, frame) -> 1);
        RedisPushBus busB = node("y", (actorId, frame) -> 0);

        busA.publish("it-push-y-" + RUN, 3003L,
                Frames.of(Frame.Cmd.CMD_PUSH, 0, PushMessage.newBuilder().build()));

        await().atMost(5, TimeUnit.SECONDS).until(() -> busB.missedLocallyCount() == 1);
        assertThat(busB.deliveredCount()).isZero();
        assertThat(busB.unreadableCount()).isZero();
    }

    @Test
    @DisplayName("频道上有脏数据（别的版本/手工写入）：跳过并计数，不抛异常、不影响后续帧")
    void garbagePayloadIsSkipped() {
        List<Frame> received = new CopyOnWriteArrayList<>();
        RedisPushBus bus = node("garbage", (actorId, frame) -> {
            received.add(frame);
            return 1;
        });

        // 直接往这个节点的频道写一段读不懂的内容
        redis.convertAndSend(ClusterKeys.push("it-push-garbage-" + RUN), "{ this is not json");

        Frame frame = Frames.of(Frame.Cmd.CMD_PUSH, 0, PushMessage.newBuilder().build());
        bus.publish("it-push-garbage-" + RUN, 4004L, frame);

        await().atMost(5, TimeUnit.SECONDS).until(() -> !received.isEmpty());
        assertThat(received).as("脏数据不能把监听线程弄死，后续帧仍要能投达").hasSize(1);
        assertThat(bus.unreadableCount()).isEqualTo(1);
        assertThat(bus.deliveredCount()).isEqualTo(1);
    }
}
