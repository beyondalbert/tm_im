package com.tm.im.core.config;

import com.tm.im.common.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 节点号折算规则。
 *
 * <p>这个类不产生任何东西，它唯一的职责是防止「两个实例算出同一个 nodeId」——
 * 而重复 nodeId 会导致重复 ID，且 Snowflake 在自己这一侧无法察觉。
 * 因此测试的重点是<b>映射的一一对应性</b>与<b>半配置/越界必须失败</b>。
 */
class SnowflakePropertiesTest {

    private static SnowflakeProperties props(Integer worker, Integer dc) {
        SnowflakeProperties p = new SnowflakeProperties();
        p.setWorkerId(worker);
        p.setDatacenterId(dc);
        return p;
    }

    @Test
    @DisplayName("显式配置：nodeId = (datacenter << 5) | worker，且 1024 种组合互不重复")
    void explicitMappingIsInjective() {
        Set<Integer> seen = new HashSet<>();
        for (int dc = 0; dc <= SnowflakeProperties.MAX_DATACENTER_ID; dc++) {
            for (int w = 0; w <= SnowflakeProperties.MAX_WORKER_ID; w++) {
                int nodeId = props(w, dc).nodeId("ignored");
                assertThat(nodeId).as("worker=%d dc=%d", w, dc)
                        .isBetween(0, (int) SnowflakeIdGenerator.maxNodeId());
                assertThat(seen.add(nodeId)).as("nodeId 重复: worker=%d dc=%d → %d", w, dc, nodeId)
                        .isTrue();
            }
        }
        assertThat(seen).hasSize(1024);
    }

    @Test
    @DisplayName("映射是纯函数：同配置在任何实例上得到同一个 nodeId")
    void mappingIsStable() {
        assertThat(props(1, 1).nodeId("host-a")).isEqualTo(props(1, 1).nodeId("host-b"));
        assertThat(props(0, 0).nodeId("x")).isEqualTo(0);
        assertThat(props(31, 31).nodeId("x")).isEqualTo(1023);
        assertThat(props(0, 1).nodeId("x")).isEqualTo(32);
    }

    @Test
    @DisplayName("只配一半直接失败：那会用到「碰巧」的节点号")
    void partialConfigurationFails() {
        assertThatThrownBy(() -> props(1, null).nodeId("h"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不完整");
        assertThatThrownBy(() -> props(null, 1).nodeId("h"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不完整");
    }

    @Test
    @DisplayName("越界直接失败：超出位域的节点号会溢出到其他位段")
    void outOfRangeFails() {
        assertThatThrownBy(() -> props(32, 0).nodeId("h"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("worker-id");
        assertThatThrownBy(() -> props(0, 32).nodeId("h"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("datacenter-id");
        assertThatThrownBy(() -> props(-1, 0).nodeId("h"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("未配置时按主机名推导：同主机稳定，且落在合法区间")
    void unsetFallsBackToHostnameHash() {
        SnowflakeProperties p = props(null, null);
        int nodeId = p.nodeId("my-host");
        assertThat(nodeId).isEqualTo(SnowflakeIdGenerator.hashToNodeId("my-host"));
        assertThat(p.nodeId("my-host")).isEqualTo(nodeId);
        assertThat(nodeId).isBetween(0, (int) SnowflakeIdGenerator.maxNodeId());
        assertThat(p.isExplicit()).isFalse();
    }

    @Test
    @DisplayName("空字符串绑定成 null（模板里用 ${TM_WORKER_ID:} 表达「未配置」的前提）")
    void emptyStringBindsToNull() {
        // 这条断言是整套「未配置 → 按主机名推导」机制的前提：
        // 若哪天 Spring 把空串绑成 0 而不是 null，所有未显式配置的实例
        // 都会得到 nodeId=0，那是「静默产生重复 ID」的最坏情况。
        Map<String, Object> source = new HashMap<>();
        source.put("tm.snowflake.worker-id", "");
        source.put("tm.snowflake.datacenter-id", "");
        SnowflakeProperties bound = new Binder(new MapConfigurationPropertySource(source))
                .bind("tm.snowflake", Bindable.of(SnowflakeProperties.class))
                .orElseGet(SnowflakeProperties::new);

        assertThat(bound.getWorkerId()).isNull();
        assertThat(bound.getDatacenterId()).isNull();
        assertThat(bound.isExplicit()).isFalse();
    }
}
