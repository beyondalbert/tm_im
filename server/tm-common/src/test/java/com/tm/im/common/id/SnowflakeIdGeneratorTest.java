package com.tm.im.common.id;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Snowflake 生成器测试。
 *
 * <p>重点在<b>时钟回拨</b>与<b>同毫秒序号用尽</b>这两条分支——它们是这段实现里
 * 唯一会产出错误结果（重复 ID / 乱序 ID）的地方，而它们在真实环境中几乎无法
 * 按需复现。注入假时钟后，两条分支都能被精确命中。
 */
class SnowflakeIdGeneratorTest {

    /** 固定基准时刻（2027-01-15 前后），远离真实当前时间，避免与系统时钟混淆。 */
    private static final long T = 1_800_000_000_000L;

    private static SnowflakeIdGenerator withClock(int nodeId, LongSupplier clock) {
        return new SnowflakeIdGenerator(nodeId, SnowflakeIdGenerator.DEFAULT_EPOCH, 5L, clock);
    }

    @Test
    @DisplayName("基本性质：全正、单线程严格递增")
    void basicProperties() {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(7);

        long previous = -1L;
        for (int i = 0; i < 10_000; i++) {
            long id = gen.nextId();
            assertThat(id).as("ID 必须为正数，下游用 id>0 判断有效性").isPositive();
            assertThat(id).as("单节点内必须严格递增").isGreaterThan(previous);
            previous = id;
        }
    }

    @Test
    @DisplayName("并发唯一性：8 线程 × 20000 个 ID 无一重复")
    void uniqueUnderConcurrency() throws Exception {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(3);
        int threads = 8;
        int perThread = 20_000;

        Set<Long> ids = Collections.synchronizedSet(new HashSet<>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        ids.add(gen.nextId());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).as("并发生成应在超时前完成").isTrue();
        pool.shutdownNow();

        assertThat(ids).as("生成的 ID 必须全部唯一；出现重复说明 synchronized 或位域有误")
                .hasSize(threads * perThread);
    }

    @Test
    @DisplayName("节点号被正确编码：边界值 0 与 1023 可往返")
    void nodeIdIsEncodedAndDecodable() {
        for (int node : new int[]{0, 1, 512, 1023}) {
            SnowflakeIdGenerator gen = withClock(node, () -> T);
            long id = gen.nextId();
            assertThat(SnowflakeIdGenerator.nodeIdOf(id)).as("节点号 %d 必须可解回", node).isEqualTo(node);
            assertThat(gen.timestampOf(id)).isEqualTo(T);
            assertThat(SnowflakeIdGenerator.sequenceOf(id)).isZero();
        }
    }

    @Test
    @DisplayName("不同节点在同一毫秒产生的 ID 不相同（节点号确实参与位域）")
    void differentNodesDoNotCollide() {
        long a = withClock(1, () -> T).nextId();
        long b = withClock(2, () -> T).nextId();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("同毫秒 4096 个序号用尽后，自动进位到下一毫秒且不重复")
    void sequenceRolloverAdvancesTimestamp() {
        // 时钟对第 4097 次调用仍返回 T —— 这正是逼出序号回绕所需的条件：
        // 第 4096 个 ID 已用掉 sequence=4095，第 4097 次必须走 waitUntil。
        AtomicInteger calls = new AtomicInteger();
        LongSupplier clock = () -> calls.incrementAndGet() <= 4097 ? T : T + 1;

        SnowflakeIdGenerator gen = withClock(0, clock);
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 4097; i++) {
            assertThat(ids.add(gen.nextId())).as("第 %d 个 ID 发生重复", i).isTrue();
        }

        long last = gen.nextId();
        assertThat(ids.add(last)).as("进位后的 ID 不得与前面的重复").isTrue();
        assertThat(gen.timestampOf(last))
                .as("序号用尽必须等到下一毫秒，不能在同一毫秒内回绕复用序号")
                .isEqualTo(T + 1);
        assertThat(ids).hasSize(4098);
    }

    @Test
    @DisplayName("时钟回拨超过阈值：拒绝发号并抛异常（不静默降级）")
    void clockBackwardsBeyondThresholdFails() {
        AtomicInteger calls = new AtomicInteger();
        LongSupplier clock = () -> calls.incrementAndGet() == 1 ? T : T - 1000;

        SnowflakeIdGenerator gen = withClock(0, clock);
        assertThat(gen.nextId()).isPositive();

        assertThatThrownBy(gen::nextId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("时钟回拨")
                .hasMessageContaining("1000ms");
    }

    @Test
    @DisplayName("时钟回拨在阈值内：自旋等待时钟追上，仍产出递增 ID")
    void clockBackwardsWithinThresholdIsAbsorbed() {
        AtomicInteger calls = new AtomicInteger();
        LongSupplier clock = () -> switch (calls.incrementAndGet()) {
            case 1 -> T;
            case 2 -> T - 3;   // 3ms 回拨，在 5ms 阈值内
            default -> T;      // 随后追上
        };

        SnowflakeIdGenerator gen = withClock(0, clock);
        long first = gen.nextId();
        long second = gen.nextId();

        assertThat(second).as("吸收回拨后 ID 仍须递增").isGreaterThan(first);
        assertThat(gen.timestampOf(second)).as("不得产生倒退的时间戳").isEqualTo(T);
    }

    @Test
    @DisplayName("节点号越界必须在构造期拒绝（否则不同节点会产出重复 ID）")
    void nodeIdRangeIsValidated() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(-1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nodeId");
        assertThatThrownBy(() -> new SnowflakeIdGenerator((int) SnowflakeIdGenerator.maxNodeId() + 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nodeId");
    }

    @Test
    @DisplayName("纪元与回拨阈值非法时被拒绝；纪元晚于当前时间在发号时报错")
    void invalidConstructionArguments() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(0, SnowflakeIdGenerator.DEFAULT_EPOCH, -1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxBackwardMs");
        assertThatThrownBy(() -> new SnowflakeIdGenerator(0, SnowflakeIdGenerator.DEFAULT_EPOCH, 5L, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("clock");

        // 纪元晚于当前时间：构造期无法判定（时钟是注入的），必须在发号时拦住，
        // 否则时间差为负 → 左移后高位被吃掉 → 产出与其他节点重复的 ID
        SnowflakeIdGenerator futureEpoch = new SnowflakeIdGenerator(0, Long.MAX_VALUE, 5L);
        assertThatThrownBy(futureEpoch::nextId)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("早于纪元");
    }

    @Test
    @DisplayName("traceback 用不到 41 位：当前时刻相对默认纪元远未超容量")
    void timestampFitsIn41Bits() {
        long gen = new SnowflakeIdGenerator(0).nextId();
        assertThat(gen).as("时间戳位域溢出会静默截断高位").isPositive();
        assertThat(gen >>> 63).as("符号位必须为 0").isZero();
    }

    @Test
    @DisplayName("兜底节点号哈希恒落在合法区间，且短主机名不退化")
    void hashToNodeIdIsAlwaysInRange() {
        for (String seed : new String[]{"", "a", "b", "host-01", "10.0.0.1",
                "tm-app-7d9f8c-x2k4p", "很长的中文主机名用于验证哈希稳定性"}) {
            int node = SnowflakeIdGenerator.hashToNodeId(seed);
            assertThat(node).as("种子 '%s' 的节点号", seed)
                    .isBetween(0, (int) SnowflakeIdGenerator.maxNodeId());
        }
        // 单字符种子若哈希后低位趋同，会退化到同一个节点号
        assertThat(SnowflakeIdGenerator.hashToNodeId("a"))
                .as("相邻短字符串不应映射到同一节点号，否则多实例会发重复 ID")
                .isNotEqualTo(SnowflakeIdGenerator.hashToNodeId("b"));
    }
}
