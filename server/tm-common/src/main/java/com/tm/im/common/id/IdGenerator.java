package com.tm.im.common.id;

/**
 * ID 生成器抽象。
 *
 * <p>抽出来不是为了「将来可能换实现」，而是为了<b>可测</b>：
 * 消息落库路径的测试需要一个能确定性地吐出指定 ID 的实现，
 * 若直接 new SnowflakeIdGenerator() 就没法断言。
 */
public interface IdGenerator {

    /**
     * 生成下一个 ID。实现必须保证：
     * <ol>
     *   <li>线程安全；</li>
     *   <li>全局唯一；</li>
     *   <li>返回值恒为正数（下游依赖 {@code id > 0} 作为有效性判断）；</li>
     *   <li>趋势递增（同一节点内严格递增）。</li>
     * </ol>
     */
    long nextId();

    /** 归属节点号，仅用于观测与排障。 */
    int nodeId();
}
