package com.tm.im.common.id;

import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/**
 * 自研 Snowflake ID 生成器（DESIGN §3.1：序列号走应用层，不用 ShardingSphere keygen）。
 *
 * <pre>
 *  1 位符号位（恒 0，保证全正）
 * 41 位毫秒时间戳（相对 {@link #DEFAULT_EPOCH}，约 69.7 年容量）
 * 10 位节点号（0..1023）
 * 12 位毫秒内序号（0..4095，即单节点 409.6 万 ID/秒上限）
 * </pre>
 *
 * <p><b>为什么不用 ShardingSphere 内置 keygen</b>：它的 Snowflake 需要连库取 workerId
 * 或依赖 ZooKeeper，而我们要求「单个 JAR 起 N 个实例、无状态、无粘性会话」。
 * ID 生成必须是纯本地操作，否则数据库一抖动，整个写入路径就跟着抖。
 *
 * <p><b>时钟回拨的处理是刻意保守的</b>：
 * <ul>
 *   <li>回拨幅度 ≤ {@code maxBackwardMs}（默认 5ms，NTP 微调的量级）→ 自旋等待时钟追上。
 *       这是可恢复的，等几百微秒就过去了。</li>
 *   <li>回拨幅度更大 → <b>直接抛异常</b>，不做静默降级。
 *       因为更大的回拨意味着「可能已经发出过未来时间戳的 ID」，
 *       此时若继续发放，会破坏「趋势递增」这一下游赖以排序的性质。
 *       宁可让这一批写入失败并告警，也不能悄悄产生乱序 ID。</li>
 * </ul>
 */
public final class SnowflakeIdGenerator implements IdGenerator {

    /** 起始纪元：2024-01-01T00:00:00Z。改动它会让新旧 ID 不可比较，故冻结。 */
    public static final long DEFAULT_EPOCH = 1_704_067_200_000L;

    /** NTP 平滑微调的典型幅度，超过它就不是「微调」了。 */
    public static final long DEFAULT_MAX_BACKWARD_MS = 5L;

    private static final int NODE_BITS = 10;
    private static final int SEQUENCE_BITS = 12;
    private static final long MAX_NODE_ID = (1L << NODE_BITS) - 1L;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1L;
    private static final int NODE_SHIFT = SEQUENCE_BITS;
    private static final int TIMESTAMP_SHIFT = NODE_BITS + SEQUENCE_BITS;

    private final long epoch;
    private final int nodeId;
    private final long maxBackwardMs;

    /**
     * 时间源。抽出来<b>纯粹是为了可测</b>：时钟回拨是这条实现里最容易写错、
     * 也最难自然复现的分支（要等 NTP 真的把钟拨回去）。注入后可以用一个
     * 「先返回未来、再返回过去」的假时钟精确命中它。
     */
    private final LongSupplier clock;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(int nodeId) {
        this(nodeId, DEFAULT_EPOCH, DEFAULT_MAX_BACKWARD_MS);
    }

    public SnowflakeIdGenerator(int nodeId, long epoch, long maxBackwardMs) {
        this(nodeId, epoch, maxBackwardMs, System::currentTimeMillis);
    }

    public SnowflakeIdGenerator(int nodeId, long epoch, long maxBackwardMs, LongSupplier clock) {
        if (nodeId < 0 || nodeId > MAX_NODE_ID) {
            throw new IllegalArgumentException(
                    "nodeId 必须在 0.." + MAX_NODE_ID + " 之间，实际为 " + nodeId
                            + "；超出会导致 ID 位域溢出，不同节点产生重复 ID");
        }
        if (maxBackwardMs < 0) {
            throw new IllegalArgumentException("maxBackwardMs 不能为负");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock 不能为 null");
        }
        this.nodeId = nodeId;
        this.epoch = epoch;
        this.maxBackwardMs = maxBackwardMs;
        this.clock = clock;
    }

    @Override
    public synchronized long nextId() {
        long timestamp = clock.getAsLong();

        if (timestamp < lastTimestamp) {
            long drift = lastTimestamp - timestamp;
            if (drift > maxBackwardMs) {
                throw new IllegalStateException(
                        "检测到时钟回拨 " + drift + "ms，超过容忍阈值 " + maxBackwardMs
                                + "ms；拒绝发放 ID 以避免产生乱序 ID。请检查 NTP 同步与虚拟化时钟。");
            }
            timestamp = waitUntil(lastTimestamp);
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // 本毫秒 4096 个序号已用尽，等到下一毫秒
                timestamp = waitUntil(lastTimestamp + 1);
            }
        }
        if (timestamp != lastTimestamp) {
            sequence = 0L;
        }
        lastTimestamp = timestamp;

        long delta = timestamp - epoch;
        // 41 位时间戳容量约 69.7 年。溢出会静默截断高位，导致新旧 ID 回绕后重复，
        // 因此宁可在这里报错也不要悄悄产生一个「时间倒流」的 ID。
        if (delta < 0) {
            throw new IllegalStateException(
                    "当前时间 " + timestamp + " 早于纪元 " + epoch + "，请检查 epoch 配置与系统时钟");
        }
        if (delta >= (1L << 41)) {
            throw new IllegalStateException(
                    "距今已超过 41 位时间戳容量（约 69.7 年），需要更换纪元（当前 epoch=" + epoch + "）");
        }

        return (delta << TIMESTAMP_SHIFT) | ((long) nodeId << NODE_SHIFT) | sequence;
    }

    private long waitUntil(long targetTimestamp) {
        long now = clock.getAsLong();
        while (now < targetTimestamp) {
            // 亚毫秒级休眠：不占用 CPU，又不至于睡过几十个 ID 的时间窗
            LockSupport.parkNanos(100_000L);
            now = clock.getAsLong();
        }
        return now;
    }

    @Override
    public int nodeId() {
        return nodeId;
    }

    public long epoch() {
        return epoch;
    }

    // ==================== 解码：排障时从 ID 反查生成时间 ====================

    public long timestampOf(long id) {
        return (id >>> TIMESTAMP_SHIFT) + epoch;
    }

    public static int nodeIdOf(long id) {
        return (int) ((id >>> NODE_SHIFT) & MAX_NODE_ID);
    }

    public static long sequenceOf(long id) {
        return id & SEQUENCE_MASK;
    }

    /**
     * 把任意字符串种子稳定映射到合法节点号区间。
     *
     * <p><b>注意其局限</b>：这只是「无配置时的兜底」。哈希必然碰撞，
     * 生产环境必须显式配置节点号（{@code tm.snowflake.node-id} 或 {@code TM_NODE_ID}），
     * 由部署系统保证唯一。这里的兜底只是让单机开发不用配任何东西。
     */
    public static int hashToNodeId(String seed) {
        int h = seed == null ? 0 : seed.hashCode();
        // 混合高低位，避免短主机名（如 "a"、"b"）哈希后低位趋同
        h ^= (h >>> 16);
        h *= 0x7feb352d;
        h ^= (h >>> 15);
        return (int) (Math.floorMod(h, MAX_NODE_ID + 1));
    }

    public static long maxNodeId() {
        return MAX_NODE_ID;
    }
}
