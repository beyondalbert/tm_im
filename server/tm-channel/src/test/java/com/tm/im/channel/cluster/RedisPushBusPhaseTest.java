package com.tm.im.channel.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨节点推送总线的启动相位（DESIGN §7.4 的时序）。
 *
 * <p>这条相位只错一次就再也不会有人发现：订阅晚于「宣布本节点存活」时，
 * 从宣布到订阅之间投过来的帧会全部丢失——而发布方报告成功、接收方什么也没收到，
 * 日志里两边都很干净。丢掉那一帧的后果只是「晚一点通过 SYNC 补齐」，
 * 但它完全没必要发生。
 *
 * <p>本测试不需要 Redis：{@code getPhase()} 是纯函数，而相位本身就是契约。
 * 真正的订阅行为由 {@code PushBusRedisIT} 在真实 Redis 上验证。
 */
class RedisPushBusPhaseTest {

    @Test
    @DisplayName("订阅（400）早于节点探活（500）与 Netty 接受连接（1000）")
    void subscribesBeforeNodeAnnouncesAndServerAccepts() {
        assertThat(RedisPushBus.PHASE)
                .as("订阅必须早于 NodeHeartbeat：否则「我已经活着」公布出去之后，"
                        + "别人投来的帧还没有人在听")
                .isLessThan(NodeHeartbeat.PHASE);
        assertThat(RedisPushBus.PHASE)
                .as("也必须早于 NettyServer(1000)：有连接之前就该听得见")
                .isLessThan(1000);
    }
}
