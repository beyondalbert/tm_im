package com.tm.im.channel.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 节点标识的推导规则与心跳/TTL 的自洽性。
 *
 * <p>这些规则都是「改坏了照样能跑」的那一类：nodeId 写成常量、心跳间隔大于 TTL、
 * TTL 写成 0 —— 单机自测全都看不出来（只有一个节点时，判活永远返回「活着」），
 * 而生产上是「跨节点推送间歇性失效，其它一切正常」。
 */
class NodePropertiesTest {

    @Test
    @DisplayName("默认 auto：nodeId 由「主机名:Netty 端口」推导")
    void autoDerivesFromHostnameAndPort() {
        NodeProperties properties = new NodeProperties();

        assertThat(properties.resolve("node-a", 8090).nodeId()).isEqualTo("node-a:8090");
        assertThat(properties.resolve("node-a", 8091).nodeId())
                .as("同主机多实例靠端口区分：派生值必须包含端口，否则两个实例会撞成同一个 nodeId")
                .isEqualTo("node-a:8091");
        assertThat(properties.resolve("node-b", 8090).nodeId()).isEqualTo("node-b:8090");
    }

    @Test
    @DisplayName("显式配置优先；空串、纯空白、大小写不同的 auto 都当未配置")
    void explicitIdWinsAndBlanksFallBackToAuto() {
        NodeProperties properties = new NodeProperties();

        properties.setId("  tm-prod-07  ");
        assertThat(properties.resolve("node-a", 8090).nodeId())
                .as("应当去掉首尾空白：带空格的 nodeId 会写出一个谁都查不到的键")
                .isEqualTo("tm-prod-07");

        properties.setId("AUTO");
        assertThat(properties.resolve("node-a", 8090).nodeId()).isEqualTo("node-a:8090");

        properties.setId("   ");
        assertThat(properties.resolve("node-a", 8090).nodeId()).isEqualTo("node-a:8090");

        properties.setId(null);
        assertThat(properties.resolve("node-a", 8090).nodeId()).isEqualTo("node-a:8090");
    }

    @Test
    @DisplayName("NodeIdentity 带上主机与端口：运维看到 nodeId 时不必再翻日志找它在哪")
    void identityCarriesLocation() {
        NodeProperties properties = new NodeProperties();
        NodeIdentity identity = properties.resolve("node-a", 8090);

        assertThat(identity.host()).isEqualTo("node-a");
        assertThat(identity.nettyPort()).isEqualTo(8090);
        assertThat(identity.toString()).isEqualTo("node-a:8090@node-a:8090");
    }

    @Test
    @DisplayName("心跳间隔必须小于 TTL：否则本节点会被反复判死，表现为跨节点推送间歇性失效")
    void heartbeatMustBeSmallerThanTtl() {
        NodeProperties properties = new NodeProperties();
        properties.setTtl(Duration.ofSeconds(10));
        properties.setHeartbeatSeconds(10);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("heartbeat-seconds")
                .hasMessageContaining("ttl");

        properties.setHeartbeatSeconds(11);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("TTL 与心跳都必须为正：0 或负数不能「解释成某种合理行为」")
    void ttlAndHeartbeatMustBePositive() {
        NodeProperties ttlZero = new NodeProperties();
        ttlZero.setTtl(Duration.ZERO);
        assertThatThrownBy(ttlZero::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tm.node.ttl");

        NodeProperties heartbeatZero = new NodeProperties();
        heartbeatZero.setHeartbeatSeconds(0);
        assertThatThrownBy(heartbeatZero::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("heartbeat-seconds");

        NodeProperties nullTtl = new NodeProperties();
        nullTtl.setTtl(null);
        assertThatThrownBy(nullTtl::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tm.node.ttl");
    }

    @Test
    @DisplayName("默认值相互自洽（45s TTL / 15s 心跳），且与配置模板里的取值一致")
    void defaultsAreSelfConsistent() {
        NodeProperties properties = new NodeProperties();

        properties.validate();     // 不自洽的话这里就会抛

        assertThat(properties.getId())
                .as("默认值必须与配置模板里的 `${TM_NODE_ID:auto}` 一致："
                        + "模板校验器只能读懂字符串字面量，所以常量与字段不能各改一半")
                .isEqualTo(NodeProperties.AUTO)
                .isEqualTo("auto");
        assertThat(properties.getTtl()).isEqualTo(Duration.ofSeconds(45));
        assertThat(properties.getHeartbeatSeconds())
                .as("45s / 15s = 连丢 2 次续期才判死")
                .isEqualTo(15);
    }

    @Test
    @DisplayName("resolve 会先校验：非法组合不该「先算出一个 nodeId 再慢慢出问题」")
    void resolveValidatesFirst() {
        NodeProperties properties = new NodeProperties();
        properties.setHeartbeatSeconds(600);      // > 默认 TTL 45s

        assertThatThrownBy(() -> properties.resolve("node-a", 8090))
                .isInstanceOf(IllegalStateException.class);
    }
}
