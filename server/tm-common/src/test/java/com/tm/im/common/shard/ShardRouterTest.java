package com.tm.im.common.shard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分片路由测试。
 *
 * <p>其中 {@link #convId100GoesToMessage4()} 就是 DESIGN §14 里 M1 阶段的验收标准
 * （「插入 conv_id=100 的数据落到 message_4」）在<b>不连数据库</b>前提下的等价形式。
 * 数据库版本、Redis 可用性这些外部阻塞都与它无关，因此它可以一直跑。
 *
 * <p>{@link #configAndDdlCarryTheSameShardingFacts()} 会真的打开
 * {@code deploy/conf/sharding.yaml.example} 与 {@code deploy/sql/01-schema.sql} 读原文。
 * 这一点必须说清楚：把常量与测试里手打的字面量比一遍是<b>自证</b>——
 * YAML 里写什么它都会通过。只有读到文件内容，配置漂移才可能被拦下。
 */
class ShardRouterTest {

    private static final String SHARDING_YAML = "deploy/conf/sharding.yaml.example";
    private static final String SCHEMA_SQL = "deploy/sql/01-schema.sql";

    @Test
    @DisplayName("M1 验收：conv_id=100 → message_4")
    void convId100GoesToMessage4() {
        assertThat(ShardRouter.shardOf(100L)).isEqualTo(4);
        assertThat(ShardRouter.physicalTable(100L)).isEqualTo("message_4");
    }

    @Test
    @DisplayName("边界：1→message_1、15→message_15、16 回绕→message_0")
    void boundaryValues() {
        assertThat(ShardRouter.physicalTable(1L)).isEqualTo("message_1");
        assertThat(ShardRouter.physicalTable(15L)).isEqualTo("message_15");
        assertThat(ShardRouter.physicalTable(16L)).as("16 % 16 = 0，应回绕到 message_0").isEqualTo("message_0");
        assertThat(ShardRouter.physicalTable(17L)).isEqualTo("message_1");
        assertThat(ShardRouter.physicalTable((long) ShardRouter.SHARD_COUNT * 1000)).isEqualTo("message_0");
    }

    @Test
    @DisplayName("1..16 的表名序列与手算结果逐项一致")
    void firstSixteenMapExactly() {
        Map<Long, String> expected = new HashMap<>();
        expected.put(1L, "message_1");
        expected.put(2L, "message_2");
        expected.put(3L, "message_3");
        expected.put(4L, "message_4");
        expected.put(5L, "message_5");
        expected.put(6L, "message_6");
        expected.put(7L, "message_7");
        expected.put(8L, "message_8");
        expected.put(9L, "message_9");
        expected.put(10L, "message_10");
        expected.put(11L, "message_11");
        expected.put(12L, "message_12");
        expected.put(13L, "message_13");
        expected.put(14L, "message_14");
        expected.put(15L, "message_15");
        expected.put(16L, "message_0");

        expected.forEach((convId, table) ->
                assertThat(ShardRouter.physicalTable(convId)).as("conv_id=%d", convId).isEqualTo(table));
    }

    @Test
    @DisplayName("非正数 conv_id 被拒绝：直接取模会产出非法表名 message_-1")
    void nonPositiveConvIdIsRejected() {
        assertThatThrownBy(() -> ShardRouter.shardOf(0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("必须为正数");
        assertThatThrownBy(() -> ShardRouter.physicalTable(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .as("若直接取模，-1 % 16 == -1 会得到 message_-1，一个 ShardingSphere 找不到的表名")
                .hasMessageContaining("message_-N");
    }

    @Test
    @DisplayName("越界分片下标被拒绝")
    void shardIndexRangeChecked() {
        assertThat(ShardRouter.physicalTableByShard(0)).isEqualTo("message_0");
        assertThat(ShardRouter.physicalTableByShard(15)).isEqualTo("message_15");
        assertThatThrownBy(() -> ShardRouter.physicalTableByShard(16))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShardRouter.physicalTableByShard(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("分布均匀：连续 16000 个 conv_id 覆盖全部 16 个分片，每片恰好 1000 个")
    void distributionIsEven() {
        int[] counts = new int[ShardRouter.SHARD_COUNT];
        for (long convId = 1; convId <= 16_000L; convId++) {
            counts[ShardRouter.shardOf(convId)]++;
        }
        for (int shard = 0; shard < ShardRouter.SHARD_COUNT; shard++) {
            assertThat(counts[shard])
                    .as("分片 message_%d 的落点数量；不均在扩容时会变成数据倾斜", shard)
                    .isEqualTo(1000);
        }
    }

    @Test
    @DisplayName("大 conv_id（Snowflake 量级）不溢出")
    void largeConvIdsDoNotOverflow() {
        long snowflakeLike = 1_234_567_890_123_456_789L;
        int shard = ShardRouter.shardOf(snowflakeLike);
        assertThat(shard).isBetween(0, ShardRouter.SHARD_COUNT - 1);
        assertThat(ShardRouter.physicalTable(snowflakeLike))
                .isEqualTo("message_" + (snowflakeLike % ShardRouter.SHARD_COUNT));

        // 同时盯住上界：Long.MAX_VALUE 取模仍须落在合法区间，
        // 且 (int) 强转不能把值截成负数（分片下标是 int，转换点就在这里）。
        assertThat(ShardRouter.shardOf(Long.MAX_VALUE)).isBetween(0, ShardRouter.SHARD_COUNT - 1);
    }

    /**
     * 读配置与建表脚本的原文，断言它们承载的分片口径与本类常量完全一致。
     *
     * <p><b>为什么不用 YAML 解析器</b>：这里要验的是「文件里写的就是这个字符串」，
     * 精确子串匹配比解析后取值更能反映意图——解析会容忍键序、缩进、注释的任意变化，
     * 而这些恰恰是 review 时最该看见的部分。真正的结构化校验在
     * {@code tools/validate_yaml.py}（28 项，已做变异测试）。
     */
    @Test
    @DisplayName("配置原文一致：sharding.yaml 与 01-schema.sql 里写的就是本类常量")
    void configAndDdlCarryTheSameShardingFacts() throws IOException {
        Path root = repoRoot();
        String yaml = Files.readString(root.resolve(SHARDING_YAML), StandardCharsets.UTF_8);
        String ddl = Files.readString(root.resolve(SCHEMA_SQL), StandardCharsets.UTF_8);

        assertThat(yaml).as("分片列")
                .contains("shardingColumn: " + ShardRouter.SHARDING_COLUMN);
        assertThat(yaml).as("INLINE 算法名")
                .contains("shardingAlgorithmName: " + ShardRouter.INLINE_ALGORITHM);
        assertThat(yaml).as("INLINE 表达式")
                .contains("algorithm-expression: " + ShardRouter.INLINE_EXPRESSION);
        assertThat(yaml).as("物理节点")
                .contains("actualDataNodes: " + ShardRouter.ACTUAL_DATA_NODES);
        assertThat(yaml).as("5.5.3 的键名是 actualDataNodes；写成 dataNodes 会被静默忽略")
                .doesNotContain("dataNodes: ds_0.");

        Set<Integer> declared = new TreeSet<>();
        Matcher m = Pattern.compile("CREATE TABLE IF NOT EXISTS `" + ShardRouter.LOGIC_TABLE + "_(\\d+)`")
                .matcher(ddl);
        while (m.find()) {
            declared.add(Integer.parseInt(m.group(1)));
        }
        Set<Integer> expected = IntStream.range(0, ShardRouter.SHARD_COUNT).boxed()
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<Integer> missing = new ArrayList<>(expected);
        missing.removeAll(declared);
        assertThat(missing).as("DDL 中缺失的物理分片表编号（路由算出的表名必须真实存在）").isEmpty();

        List<Integer> extra = new ArrayList<>(declared);
        extra.removeAll(expected);
        assertThat(extra).as("DDL 中多余的 message_N（超出 SHARD_COUNT 的表永远不会被路由到，"
                + "通常是改了 DDL 却忘了改 SHARD_COUNT 与 sharding.yaml）").isEmpty();

        assertThat(declared).as("message_N 的编号集合必须恰为 0..SHARD_COUNT-1").isEqualTo(expected);
    }

    /**
     * 从当前工作目录向上找仓库根。
     *
     * <p>找不到时直接失败而不是 {@code assumptions.assumeTrue(...)} 跳过——
     * 跳过会让「配置漂移」和「目录没找对」看起来一模一样，都是绿的。
     */
    private static Path repoRoot() {
        Path start = Paths.get("").toAbsolutePath();
        for (Path p = start; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve(SHARDING_YAML))) {
                return p;
            }
        }
        throw new IllegalStateException(
                "从 " + start + " 向上找不到包含 " + SHARDING_YAML + " 的仓库根目录；"
                        + "该测试需要读取仓库内的配置原文，请在仓库内运行 mvn test");
    }
}
