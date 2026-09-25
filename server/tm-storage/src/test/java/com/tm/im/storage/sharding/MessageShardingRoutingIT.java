package com.tm.im.storage.sharding;

import com.tm.im.storage.it.ItEnv;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ShardingSphere 真实路由验证 —— M1 的核心验收。
 *
 * <p><b>为什么这个测试非有不可</b>：数据库层已经用物理表名验证过
 * 「conv_id=100 写在 message_4 里」（{@code tools/bootstrap_db.py} 的冒烟测试），
 * 但那是我自己算好表名再写进去的，<b>等于把路由算法抄了一遍</b>。
 * 真正要证明的是另一件事：应用只写逻辑表名 {@code message}，
 * ShardingSphere 自己能找到 {@code message_4}。
 * 这两件事看着接近，失败方式却完全不同——后者错了，数据会安静地散落在错误的表里。
 *
 * <p><b>怎么证明"确实落在物理表里"</b>：必须用一条绕过 ShardingSphere 的
 * 直连连接去查 {@code information_schema} 与 16 张物理表。
 * 如果只用同一个 ShardingSphere 连接去读回，读和写走的是同一套（可能错了的）路由，
 * 依然能读到自己刚写的数据——那是自证。
 *
 * <p><b>为什么逻辑连接必须是 autoCommit=true</b>：上面那条"用直连连接核对"
 * 决定了这一点。直连连接是<b>另一个会话</b>，看不见未提交的数据；若逻辑连接
 * 一直开着事务，直连连接读到的就是空表（断言得到 {@code {}}），
 * 而它执行的 DELETE 还会被未提交的行锁挡住，一路撞到
 * {@code innodb_lock_wait_timeout}（默认 50 秒）。两个症状都像是路由错了，
 * 实际只是事务边界问题，极具误导性。所以本测试写一行即提交，
 * 数据用 {@link #TAG} 精确清理，不依赖回滚。
 *
 * <p><b>清理放在 {@code @AfterEach}</b>：断言失败会中断测试方法，
 * 清理若写在方法末尾就永远不会执行，脏数据会留在库里。
 *
 * <p><b>前置条件</b>：需要真实 MySQL 与凭据。默认 {@code mvn test} 不跑本类
 * （surefire 排除了 {@code *IT.java}）；用 {@code -Pit} 显式激活，此时
 * 缺少配置会<b>失败</b>而不是跳过——跳过会让"没验证"和"验证通过"都是绿的。
 *
 * <pre>
 *   uv run python tools/gen_runtime_config.py
 *   mvn -pl tm-storage -am test -Pit "-Dtm.it.config=&lt;上一步输出的路径&gt;"
 * </pre>
 */
class MessageShardingRoutingIT {

    private static final String LOGIC_TABLE = "message";
    private static final int SHARD_COUNT = 16;

    /** 本次运行写入数据的标记，用于精确清理，避免误删他人数据。 */
    private static final String TAG = "it" + Long.toHexString(System.nanoTime());

    /**
     * 主键来源。刻意不用 {@code System.nanoTime()} 直接当主键：
     * 幂等键测试里连着插两行，若 id 恰好相同，第二行会因为主键冲突失败，
     * 于是"唯一键生效"这条断言就变成因错误原因通过。
     */
    private static final AtomicLong ID_SEQ = new AtomicLong(System.nanoTime());

    private static Connection sharding;   // 走 ShardingSphere，只认逻辑表
    private static Connection physical;   // 直连 MySQL，只能看到物理表
    private static String jdbcUrl;

    @BeforeAll
    static void setUp() throws Exception {
        Path cfg = resolveConfig();

        // ---- 逻辑连接：jdbc:shardingsphere:absolutepath:<配置文件> ----
        jdbcUrl = "jdbc:shardingsphere:absolutepath:" + cfg.toAbsolutePath();
        sharding = DriverManager.getConnection(jdbcUrl);
        // 保持默认的 autoCommit=true，理由见类注释
        assertThat(sharding.getAutoCommit())
                .as("逻辑连接必须在自动提交模式下，否则直连连接看不到写入的数据")
                .isTrue();

        // ---- 物理连接：从同一份配置里取出真实连接信息 ----
        String yaml = Files.readString(cfg, StandardCharsets.UTF_8);
        String url = firstGroup(yaml, "^\\s*jdbcUrl:\\s*(\\S+)\\s*$");
        String user = firstGroup(yaml, "^\\s*username:\\s*(\\S+)\\s*$");
        String pwd = firstGroup(yaml, "^\\s*password:\\s*(\\S+)\\s*$");
        physical = DriverManager.getConnection(url, user, pwd);

        System.out.println("[IT] 逻辑连接 = " + jdbcUrl);
        System.out.println("[IT] 物理连接 = " + mask(url));
        System.out.println("[IT] 标记 TAG = " + TAG);
    }

    @AfterEach
    void cleanAfterEachTest() throws SQLException {
        cleanup();
    }

    @AfterAll
    static void tearDown() throws Exception {
        try {
            if (sharding != null) {
                sharding.close();
            }
        } finally {
            if (physical != null) {
                physical.close();
            }
        }
    }

    /**
     * 定位运行时配置。
     *
     * <p>刻意不写成"找不到就跳过"：那会让 CI 上"配置没生成"与"路由正常"
     * 呈现同一个绿色。这里直接抛出带修复命令的异常。
     *
     * <p><b>为什么默认去找 {@code deploy/conf/runtime/sharding.yaml}</b>：
     * 本类曾经要求必须传 {@code -Dtm.it.config}，而同模块的 {@code ItEnv}
     * 已经能从约定路径自己找到它（并向上一层一层找）——于是「同一件事两套约定」：
     * 一条路没人传参数时直接失败，另一条路什么都不传就能跑。
     * 失败那条会让人以为是环境问题（“缺凭据”），而实际只是参数没给。
     * 现在两边共用一份约定：{@code -Dtm.it.config} 仍然是个覆盖口，
     * 但不再是必填项。
     */
    private static Path resolveConfig() {
        String prop = System.getProperty("tm.it.config", "").trim();
        if (prop.isEmpty()) {
            return Paths.get(ItEnv.shardingConfig());
        }
        Path p = Paths.get(prop);
        if (!p.isAbsolute()) {
            // 相对路径按仓库根解析：surefire 的工作目录是模块目录（server/tm-storage），
            // 而仓库根在它上面两层。不写死层数：哪一层能找到这个相对路径，
            // 哪一层就是仓库根（与 ItEnv 同一做法）。
            p = findUpwards(prop);
        }
        if (!Files.isRegularFile(p)) {
            throw new IllegalStateException(
                    "tm.it.config 指向的文件不存在: " + p + "\n"
                            + "  生成： uv run python tools/gen_runtime_config.py");
        }
        return p;
    }

    /** 从当前目录逐层向上找 {@code relative}；找不到时返回「当前目录 + relative」。 */
    private static Path findUpwards(String relative) {
        Path start = Paths.get("").toAbsolutePath();
        for (Path dir = start; dir != null && dir.getNameCount() > 0; dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return start.resolve(relative);
    }

    private static String firstGroup(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.MULTILINE).matcher(text);
        if (!m.find()) {
            throw new IllegalStateException("sharding.yaml 里匹配不到 " + regex);
        }
        return m.group(1);
    }

    /** 打印连接串时遮蔽口令——测试日志同样会进 CI，一样要脱敏。 */
    private static String mask(String url) {
        return url.replaceAll("(?i)(password=)[^&]*", "$1***");
    }

    private static void cleanup() throws SQLException {
        // 按标记精确删除 16 张物理表里的本次数据。
        // 不用 TRUNCATE / DROP：同一个库用于开发，误伤面太大。
        try (Statement st = physical.createStatement()) {
            int removed = 0;
            for (int i = 0; i < SHARD_COUNT; i++) {
                removed += st.executeUpdate(
                        "DELETE FROM message_" + i + " WHERE client_msg_id LIKE '" + TAG + "%'");
            }
            if (removed > 0) {
                System.out.println("[IT] 清理 " + removed + " 行");
            }
        }
    }

    /** 16 张物理表里，本次标记的数据各出现在哪张表、几行。 */
    private Map<String, Integer> locatePhysically() throws SQLException {
        Map<String, Integer> found = new LinkedHashMap<>();
        try (Statement st = physical.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT TABLE_NAME FROM information_schema.TABLES "
                             + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 'message\\_%'")) {
            List<String> tables = new ArrayList<>();
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
            for (String t : tables) {
                try (ResultSet r2 = st.executeQuery(
                        "SELECT COUNT(*) FROM `" + t + "` WHERE client_msg_id LIKE '" + TAG + "%'")) {
                    r2.next();
                    int n = r2.getInt(1);
                    if (n > 0) {
                        found.put(t, n);
                    }
                }
            }
        }
        return found;
    }

    /** 只用逻辑表名写入一行（自动提交），返回受影响行数。 */
    private int insertViaLogicTable(long convId, long seq, String tagSuffix) throws SQLException {
        String sql = "INSERT INTO " + LOGIC_TABLE
                + " (id, conv_id, seq, sender_id, msg_type, content, client_msg_id, created_at)"
                + " VALUES (?,?,?,?,1,?,?,NOW(3))";
        try (PreparedStatement ps = sharding.prepareStatement(sql)) {
            ps.setLong(1, ID_SEQ.incrementAndGet());
            ps.setLong(2, convId);
            ps.setLong(3, seq);
            ps.setLong(4, 900000000000000001L);
            ps.setString(5, "{\"text\":\"routing probe\"}");
            ps.setString(6, TAG + tagSuffix);
            return ps.executeUpdate();
        }
    }

    @Test
    @DisplayName("M1 验收：只写逻辑表 message，conv_id=100 的行必须物理落在 message_4")
    void convId100LandsInMessage4() throws Exception {
        int n = insertViaLogicTable(100L, 1001L, "-a");
        assertThat(n).as("逻辑表写入应成功").isEqualTo(1);

        Map<String, Integer> where = locatePhysically();
        assertThat(where)
                .as("直连物理表核对：conv_id=100 的数据应当且只应当在 message_4 里")
                .containsOnlyKeys("message_4");
        assertThat(where.get("message_4")).isEqualTo(1);

        // 路由的读路径也要成立：同一个逻辑表能把它查回来
        try (PreparedStatement ps = sharding.prepareStatement(
                "SELECT seq FROM " + LOGIC_TABLE + " WHERE conv_id=? AND client_msg_id=?")) {
            ps.setLong(1, 100L);
            ps.setString(2, TAG + "-a");
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("经 ShardingSphere 读回应能命中").isTrue();
                assertThat(rs.getLong(1)).isEqualTo(1001L);
            }
        }
    }

    @Test
    @DisplayName("不同 conv_id 路由到不同物理表，且落点与 conv_id % 16 一致")
    void differentConversationsRouteToDifferentShards() throws Exception {
        // 这一组里 1 和 17 会撞进同一个分片（1 % 16 == 17 % 16 == 1），
        // 是故意选的：它能同时验证"不同表分开落"与"同余的分片共用一张表"。
        long[] convIds = {1L, 16L, 17L, 255L};
        for (int i = 0; i < convIds.length; i++) {
            insertViaLogicTable(convIds[i], 2001L + i, "-b" + i);
        }

        // 期望值用 Map 而不是 List：conv_id 有重余数时，期望的落点是
        // {message_1 -> 2 行}，用 List 写就会出现重复元素，
        // 而 keySet() 天然去重，那种断言永远不可能成立。
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("message_1", 2);    // conv_id 1 与 17
        expected.put("message_0", 1);    // conv_id 16（16 取模回绕到 0）
        expected.put("message_15", 1);   // conv_id 255
        for (long c : convIds) {
            String table = "message_" + (c % SHARD_COUNT);
            assertThat(expected).as("手算结果自洽性检查").containsKey(table);
        }

        assertThat(locatePhysically())
                .as("落点分布应等于按 conv_id 对 16 取模手算的结果")
                .containsExactlyInAnyOrderEntriesOf(expected);

        // 逐条独立核对，出错时能直接看出是哪一条
        for (int i = 0; i < convIds.length; i++) {
            String table = "message_" + (convIds[i] % SHARD_COUNT);
            try (PreparedStatement ps = physical.prepareStatement(
                    "SELECT COUNT(*) FROM `" + table + "` WHERE client_msg_id=?")) {
                ps.setString(1, TAG + "-b" + i);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1))
                            .as("conv_id=%d 应落在 %s", convIds[i], table)
                            .isEqualTo(1);
                }
            }
        }
    }

    @Test
    @DisplayName("幂等唯一键经逻辑表依然生效：同 (conv_id,sender_id,client_msg_id) 被拒")
    void idempotencyKeyStillHoldsThroughLogicTable() throws Exception {
        insertViaLogicTable(100L, 3001L, "-c");

        assertThatThrownBy(() -> insertViaLogicTable(100L, 3002L, "-c"))
                .as("重复的幂等键必须被拒；若 ShardingSphere 把两条路由到了不同的表，"
                        + "这条断言就会失败——那正是跨分片唯一约束失效的形态")
                .isInstanceOf(SQLIntegrityConstraintViolationException.class);
    }

    @Test
    @DisplayName("Snowflake 量级的 conv_id 也能被正确路由（不溢出、不产生非法表名）")
    void snowflakeScaleConvIdRoutesCorrectly() throws Exception {
        long convId = 1_234_567_890_123_456_789L;
        insertViaLogicTable(convId, 4001L, "-d");

        int expectedShard = (int) (convId % SHARD_COUNT);
        assertThat(locatePhysically())
                .as("conv_id=%d 应落在 message_%d", convId, expectedShard)
                .containsOnlyKeys("message_" + expectedShard);

        // 物理表的实际行数也要对得上
        try (Statement st = physical.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM message_" + expectedShard
                             + " WHERE client_msg_id = '" + TAG + "-d'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("物理表名不能当逻辑表用：写 message_4 会被拒绝，而不是原样下推")
    void physicalTableNameIsRejected() throws Exception {
        // 这条断言原本写反了。我最初以为"物理表名会被原样下推"，
        // 实测 5.5.3 的行为是直接拒绝：
        //   java.sql.SQLException: Table or view 'message_4' does not exist.
        // 因为 ShardingSphere 只认配置里声明过的逻辑表，
        // message_4 不在其元数据里，于是连解析阶段都过不去。
        //
        // 这个区别有实际意义：想绕过分片直接写某张物理表是<b>做不到</b>的，
        // 不存在"悄悄写错表"的路径。
        try (Statement st = sharding.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate(
                    "INSERT INTO message_4 (id, conv_id, seq, sender_id, msg_type, content, "
                            + "client_msg_id, created_at) VALUES ("
                            + ID_SEQ.incrementAndGet() + ", 100, 5001, 900000000000000001, 1, "
                            + "'{\"text\":\"physical name probe\"}', '" + TAG + "-e', NOW(3))"))
                    .as("物理表名必须被拒绝")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("message_4");
        }

        // 反证：被拒之后库里不该有这一行
        assertThat(locatePhysically())
                .as("被拒绝的写入不应留下任何数据")
                .isEmpty();
    }
}
