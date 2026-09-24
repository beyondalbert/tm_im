package com.tm.im.core.config;

import com.tm.im.common.id.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Snowflake 节点标识配置。
 *
 * <p><b>配置键沿用 MyBatis-Plus 的惯例</b>（{@code worker-id} 5 位 + {@code datacenter-id} 5 位），
 * 因为部署系统与运维习惯里 {@code TM_WORKER_ID} / {@code TM_DC_ID} 就是这一套。
 * 两者拼成 {@link SnowflakeIdGenerator} 需要的单个 10 位 nodeId，
 * 映射固定为 {@code (datacenterId << 5) | workerId}，因此同一对取值
 * 在任何实例、任何版本上都得到同一个 nodeId。
 *
 * <p><b>这里防的是一个会静默毁数据的坑</b>：两个实例用了同一个 nodeId，
 * 会生成<b>相同</b>的 ID。数据库上表现为主键冲突（还好，能发现），
 * 但在内存队列、Redis 路由表这类没有唯一约束的地方就是静默覆盖。
 * 而 Snowflake 算法本身<b>无法</b>在自己这一侧发现重复——它没有集群视角。
 *
 * <p>所以取值规则是「显式配置优先，未配置才退化」，且<b>不允许只配一半</b>：
 * <ul>
 *   <li>worker-id 与 datacenter-id 都配了 → 用它俩；</li>
 *   <li>都没配 → 按主机名哈希（{@link SnowflakeIdGenerator#hashToNodeId}），
 *       并打 WARN。这是单机开发用的兜底：1024 个桶，两台机器就已有约 0.1% 的
 *       碰撞概率，几十台的集群必然撞上（生日悖论）。</li>
 *   <li>只配了一个 → 直接启动失败。这几乎总是配置漏了而不是有意为之，
 *       继续跑等于用一个「碰巧」的 nodeId。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "tm.snowflake")
public class SnowflakeProperties {

    private static final Logger log = LoggerFactory.getLogger(SnowflakeProperties.class);

    /** MyBatis-Plus 惯例：worker 占低 5 位。 */
    public static final int WORKER_BITS = 5;
    public static final int MAX_WORKER_ID = (1 << WORKER_BITS) - 1;

    /** 高 5 位给 datacenter。 */
    public static final int MAX_DATACENTER_ID = (1 << WORKER_BITS) - 1;

    /** 为空表示「未配置」，而不是 0——0 是合法节点号，两者必须能区分。 */
    private Integer workerId;
    private Integer datacenterId;

    public Integer getWorkerId() {
        return workerId;
    }

    public void setWorkerId(Integer workerId) {
        this.workerId = workerId;
    }

    public Integer getDatacenterId() {
        return datacenterId;
    }

    public void setDatacenterId(Integer datacenterId) {
        this.datacenterId = datacenterId;
    }

    /** 是否显式配置了节点标识（两者都配才算）。 */
    public boolean isExplicit() {
        return workerId != null && datacenterId != null;
    }

    /**
     * 折算成 Snowflake 的 10 位 nodeId。
     *
     * @param hostnameSeed 未配置时的哈希种子，通常是主机名
     */
    public int nodeId(String hostnameSeed) {
        boolean hasWorker = workerId != null;
        boolean hasDc = datacenterId != null;
        if (hasWorker != hasDc) {
            throw new IllegalStateException(
                    "tm.snowflake 配置不完整：worker-id=" + workerId + ", datacenter-id=" + datacenterId
                            + "。两者必须同时配置或同时留空——只配一半会用到一个「碰巧」的节点号，"
                            + "而重复的节点号会产生重复 ID，且 Snowflake 自身无法察觉");
        }
        if (!hasWorker) {
            int derived = SnowflakeIdGenerator.hashToNodeId(hostnameSeed);
            log.warn("未配置 tm.snowflake.worker-id / datacenter-id，按 {} 推导出 nodeId={}。"
                            + "这只适用于单机开发：多实例部署必须显式配置且各不相同，"
                            + "否则会生成重复 ID（数据库上表现为主键冲突，内存结构上则是静默覆盖）",
                    hostnameSeed, derived);
            return derived;
        }
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalStateException(
                    "tm.snowflake.worker-id 必须在 0.." + MAX_WORKER_ID + "，实际 " + workerId);
        }
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalStateException(
                    "tm.snowflake.datacenter-id 必须在 0.." + MAX_DATACENTER_ID + "，实际 " + datacenterId);
        }
        return (datacenterId << WORKER_BITS) | workerId;
    }
}
