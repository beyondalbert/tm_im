package com.tm.im.common.shard;

/**
 * 消息表分片路由 —— <b>Java 侧的真源</b>。
 *
 * <p>为什么要有这么一个只有几行的类？因为分片算法同时存在于两个地方：
 * <ol>
 *   <li>{@code deploy/conf/sharding.yaml} 的 INLINE 表达式（由 ShardingSphere 执行）；</li>
 *   <li>建表脚本里 16 张 {@code message_N} 物理表（由 MySQL 执行）。</li>
 * </ol>
 * 应用代码如果不知道映射规则，就只能靠「插进去再查出来对不对」来验证，
 * 而这恰恰是最容易掩盖错误的验证方式——写错分片后数据可能仍能读回，
 * 只是散落在错误的表里，等到分片扩容或单表数据倾斜时才发现。
 *
 * <p>把规则固化在这里之后，可以做到两件当前做不到的事：
 * <ul>
 *   <li>{@code ShardRouterTest} 不连数据库就算出 {@code conv_id=100} 应落在 {@code message_4}
 *       （M1 验收标准）；它还会真读 {@code sharding.yaml.example} 与 {@code 01-schema.sql}
 *       的原文，断言里面的表达式、分片列、物理节点与这里的常量逐字一致；</li>
 *   <li>{@code tools/validate_yaml.py} 做同一件事的结构化版本（连同分片表编号连续性、
 *       算法类型、数据源定义等，28 项）。<b>配置漂移会在构建阶段就被拦下</b>，
 *       而不是等上线后数据错位。</li>
 * </ul>
 *
 * <p>本类中所有分片口径都从 {@link #SHARD_COUNT} 推导（见 {@link #INLINE_EXPRESSION}、
 * {@link #ACTUAL_DATA_NODES}），<b>不在注释或提示语里硬编码数字</b>——
 * 硬编码的字面量改分片数时不会报错，只会静静地说谎。
 */
public final class ShardRouter {

    /** 逻辑表名。业务代码永远只写这个，16 张物理表对它透明。 */
    public static final String LOGIC_TABLE = "message";

    /** 分片数。改它必须同步改 DDL、sharding.yaml 与 {@link #INLINE_EXPRESSION}。 */
    public static final int SHARD_COUNT = 16;

    public static final String SHARDING_COLUMN = "conv_id";
    public static final String INLINE_ALGORITHM = "message_inline";

    /** 期望出现在 sharding.yaml 中的表达式，用于配置漂移断言。 */
    public static final String INLINE_EXPRESSION = "message_${" + SHARDING_COLUMN + " % " + SHARD_COUNT + "}";

    /** 期望出现在 sharding.yaml 中的物理节点描述。 */
    public static final String ACTUAL_DATA_NODES = "ds_0." + LOGIC_TABLE + "_${0.." + (SHARD_COUNT - 1) + "}";

    private static final String PHYSICAL_PREFIX = LOGIC_TABLE + "_";

    private ShardRouter() {
    }

    /**
     * 计算会话所属的分片下标。
     *
     * <p><b>为什么显式拒绝非正数而不是直接取模</b>：Java（以及 Groovy 的 INLINE 算法）
     * 中 {@code -1 % 16 == -1}，会生成物理表名 {@code message_-1}，
     * ShardingSphere 找不到该表，最终表现为一个与「ID 为负」毫无关联的
     * "no table found" 报错。在这里提前拦下，错误信息才指向真正的病因。
     */
    public static int shardOf(long convId) {
        if (convId <= 0) {
            throw new IllegalArgumentException(
                    "conv_id 必须为正数，实际为 " + convId
                            + "；分片算法 " + INLINE_EXPRESSION + " 对负数会产生非法表名 message_-N");
        }
        return (int) (convId % SHARD_COUNT);
    }

    /** 会话应落在的物理表名，例如 {@code conv_id=100} → {@code message_4}。 */
    public static String physicalTable(long convId) {
        return PHYSICAL_PREFIX + shardOf(convId);
    }

    public static String physicalTableByShard(int shard) {
        if (shard < 0 || shard >= SHARD_COUNT) {
            throw new IllegalArgumentException("分片下标越界: " + shard + "，合法范围 0.." + (SHARD_COUNT - 1));
        }
        return PHYSICAL_PREFIX + shard;
    }
}
