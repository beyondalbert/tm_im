package com.tm.im.channel.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 节点心跳：注册 → 续期 → （键丢了就重注册）→ 停机注销。
 *
 * <p>规则里最容易被忽略的一条是「续期返回 false 时必须重新注册」：
 * Redis 重启过、或运维清过键之后，如果只续期不重注册，本节点在别人眼里
 * 永远是死的 —— 而本节点一切正常，日志里只有一行 INFO 级别的「心跳已启动」。
 */
class NodeHeartbeatTest {

    /** 1s 心跳、3s TTL（校验要求心跳 < TTL，且这里只等 1~3 个周期）。 */
    private static NodeProperties properties() {
        NodeProperties properties = new NodeProperties();
        properties.setHeartbeatSeconds(1);
        properties.setTtl(Duration.ofSeconds(3));
        return properties;
    }

    @Test
    @DisplayName("启动即注册，并带上节点位置信息与配置里的 TTL")
    void startRegistersImmediately() {
        RecordingNodeRegistry nodes = new RecordingNodeRegistry();
        NodeHeartbeat heartbeat = new NodeHeartbeat(nodes,
                new NodeIdentity("node-a", "host-a", 8090), properties());

        heartbeat.start();
        try {
            assertThat(heartbeat.isRunning()).isTrue();
            assertThat(nodes.registrations()).hasSize(1);
            NodeInfo info = nodes.registrations().get(0);
            assertThat(info.nodeId()).isEqualTo("node-a");
            assertThat(info.host()).isEqualTo("host-a");
            assertThat(info.nettyPort()).isEqualTo(8090);
            assertThat(info.startedAtMs()).as("重启过没有，只能靠这个时间戳看出来").isPositive();
            assertThat(nodes.registrationTtls()).containsExactly(Duration.ofSeconds(3));
            assertThat(nodes.isAlive("node-a")).isTrue();
        } finally {
            heartbeat.stop();
        }
    }

    @Test
    @DisplayName("续期失败（键被清理/Redis 重启）→ 下一个周期重新注册，否则本节点会被永远当成死节点")
    void renewFailureTriggersReRegistration() {
        RecordingNodeRegistry nodes = new RecordingNodeRegistry();
        NodeHeartbeat heartbeat = new NodeHeartbeat(nodes,
                new NodeIdentity("node-a", "host-a", 8090), properties());

        heartbeat.start();
        try {
            await("至少一次续期", () -> nodes.renewCalls() >= 1);
            nodes.renewFails();
            await("续期失败后重新注册", () -> nodes.registrations().size() >= 2);
            assertThat(nodes.isAlive("node-a")).isTrue();
        } finally {
            heartbeat.stop();
        }
    }

    @Test
    @DisplayName("Redis 抖动（续期抛异常）不会打断心跳线程，也不会顺带重新注册")
    void renewExceptionIsSwallowedAndHeartbeatKeepsTicking() {
        RecordingNodeRegistry nodes = new RecordingNodeRegistry();
        NodeHeartbeat heartbeat = new NodeHeartbeat(nodes,
                new NodeIdentity("node-a", "host-a", 8090), properties());

        heartbeat.start();
        try {
            nodes.throwOnRenew();
            int before = nodes.renewCalls();
            await("继续下一次续期", () -> nodes.renewCalls() >= before + 2);
            assertThat(nodes.registrations())
                    .as("续期抛异常说明 Redis 不可用，此刻重新注册同样会失败；"
                            + "心跳线程按时重试即可自愈，不必在异常路径上再写一次")
                    .hasSize(1);
            assertThatCode(heartbeat::stop).doesNotThrowAnyException();
        } finally {
            heartbeat.stop();
        }
    }

    @Test
    @DisplayName("停机注销：立刻让其它节点停止把消息投给本节点，并停掉定时任务")
    void stopUnregistersAndStopsTicking() {
        RecordingNodeRegistry nodes = new RecordingNodeRegistry();
        NodeHeartbeat heartbeat = new NodeHeartbeat(nodes,
                new NodeIdentity("node-a", "host-a", 8090), properties());

        heartbeat.start();
        await("至少一次续期", () -> nodes.renewCalls() >= 1);

        heartbeat.stop();
        assertThat(heartbeat.isRunning()).isFalse();
        assertThat(nodes.unregisterCalls()).isEqualTo(1);
        assertThat(nodes.isAlive("node-a")).isFalse();

        int callsAfterStop = nodes.renewCalls();
        sleep(1_500);
        assertThat(nodes.renewCalls())
                .as("停机后不能再有续期：那会在「已注销」之后又把节点写成存活")
                .isEqualTo(callsAfterStop);
    }

    @Test
    @DisplayName("注册失败（Redis 不可用）不让服务起不来：网关照样能服务本机连接")
    void registerFailureIsNotFatal() {
        RecordingNodeRegistry nodes = new RecordingNodeRegistry();
        nodes.throwOnRegister();
        NodeHeartbeat heartbeat = new NodeHeartbeat(nodes,
                new NodeIdentity("node-a", "host-a", 8090), properties());

        assertThatCode(heartbeat::start).doesNotThrowAnyException();
        assertThat(heartbeat.isRunning()).isTrue();
        heartbeat.stop();
    }

    @Test
    @DisplayName("相位早于 NettyServer（1000）：先登记为存活，再开始接受连接")
    void phaseRunsBeforeTheNettyServer() {
        NodeHeartbeat heartbeat = new NodeHeartbeat(new RecordingNodeRegistry(),
                new NodeIdentity("node-a", "host-a", 8090), properties());

        assertThat(NodeHeartbeat.PHASE).isLessThan(1000);
        assertThat(heartbeat.getPhase()).isEqualTo(NodeHeartbeat.PHASE);
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(20);
        }
        throw new AssertionError(what + "：8s 内未达成");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
