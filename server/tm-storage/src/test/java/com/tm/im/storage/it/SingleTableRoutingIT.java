package com.tm.im.storage.it;

import org.apache.shardingsphere.infra.exception.kernel.metadata.TableNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 非分片表能不能通过 ShardingSphere 数据源访问 —— 真实 MySQL 验证。
 *
 * <p><b>为什么要单独一个测试</b>：应用<b>只有一个数据源</b>，就是
 * ShardingSphere 那个（见 application-external.yml.example）。所以「分片表能写」
 * 完全不代表「业务表能读」——它们是两条不同的路径：
 * <ul>
 *   <li>分片表 {@code message}：物理节点在 {@code !SHARDING.actualDataNodes} 里写死；</li>
 *   <li>非分片表 {@code conversation} / {@code actor} / …：靠 {@code !SINGLE} 规则登记。</li>
 * </ul>
 *
 * <p><b>本测试的由来（真实缺陷）</b>：模板里 {@code !SINGLE} 只写了
 * {@code defaultDataSource: ds_0}，没有 {@code tables} 列表。当时以为「单数据源可省略」。
 * 实测（5.5.3 + MySQL 8.4）结果是：<b>所有非分片表一律不可访问</b>，
 *
 * <pre>
 *   TableNotFoundException: Table or view 'conversation' does not exist.
 * </pre>
 *
 * 而分片表 message 读写完全正常。这个组合极具误导性——错误全部指向业务表，
 * 看起来像建表脚本没跑、连错库、或实体注解写错了。根因在
 * {@code SingleTableDataNodeLoader.load} 的第一条分支（已反编译核实）：
 * {@code configuredTables.isEmpty() && featureRequiredSingleTables.isEmpty()}
 * 时直接返回空表集，即「空列表 = 一张都不要」，既不是「全部」也不是「继承默认」。
 *
 * <p>修法是在 {@code !SINGLE.tables} 里写 {@code "*.*"}（未在分片规则里出现的表全部登记）。
 * 本测试把这件事固定下来：它失败时，症状会在**每个**业务接口上出现，
 * 而这里给出的是唯一一个能指向根因的断言。
 *
 * <p>另外两条同族断言也一并钉住：物理分表名 {@code message_0} 不能被当逻辑表用
 * （否则「悄悄写错表」会成为一条真实存在的路径），以及 DDL 里的非分片表
 * 在 ShardingSphere 眼里必须都在。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ItSpringConfig.class)
class SingleTableRoutingIT {

    /**
     * DDL 里除 message 与 message_0..15 之外的全部表。
     *
     * <p>刻意写死在这里而不是从 DDL 里动态读：这个列表就是「配置有没有漏登记」的
     * 判据，从同一个源推导出来会让断言变成自证。
     * {@code tools/validate_yaml.py} 另有 DDL ↔ 配置的交叉校验。
     */
    private static final List<String> NON_SHARDED = List.of(
            "actor", "actor_secret", "agent_profile", "conversation", "conversation_member",
            "friendship", "post", "post_like", "post_comment", "feed_item", "media");

    @Autowired
    private DataSource dataSource;

    private long count(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @DisplayName("M1/M3 前置：非分片表必须能通过 ShardingSphere 数据源访问（空 !SINGLE.tables 会让它们全部消失）")
    void nonShardedTablesAreVisible() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            List<String> broken = new ArrayList<>();
            for (String table : NON_SHARDED) {
                try {
                    count(conn, table);
                } catch (SQLException e) {
                    broken.add(table + " -> " + e.getMessage());
                }
            }
            assertThat(broken)
                    .as("这些表在 ShardingSphere 里不可见。若报 TableNotFoundException 而分片表 "
                            + "message 正常，根因是 sharding.yaml 的 !SINGLE 规则没有非空 tables 列表"
                            + "（5.5.3 里空列表 = 一张单表都不登记，而不是「全部」）")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("逻辑表 message 能查（分片路径本身没坏）")
    void shardedLogicTableIsVisible() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertThatCode(() -> count(conn, "message"))
                    .as("逻辑表 message 查询不应抛异常")
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("物理分表名不是后门：直接写 message_0 必须被拒绝")
    void physicalShardTableCannotBeUsedDirectly() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertThatThrownBy(() -> count(conn, "message_0"))
                    .as("物理表名不该能绕过路由。5.5.3 的行为是直接在解析阶段拒绝，"
                            + "也就是说「悄悄写错表」这条路径根本不存在")
                    .isInstanceOf(TableNotFoundException.class)
                    .hasMessageContaining("message_0");
        }
    }

    @Test
    @DisplayName("通配也不会把分片物理表暴露出来（*.* 与 !SHARDING 不冲突）")
    void wildcardDoesNotExposeShardTables() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // 若 !SINGLE 的 *.* 把 message_0..15 也登记成了单表，
            // 同一条 SQL 可能被两条规则同时匹配（路由结果不确定）；
            // 而 getExcludedTables 的职责正是把分片表排除掉。
            for (int i = 0; i < 16; i++) {
                String physical = "message_" + i;
                assertThatThrownBy(() -> count(conn, physical))
                        .as("%s 不应可访问：分片表只允许经逻辑表 message 访问", physical)
                        .isInstanceOf(TableNotFoundException.class);
            }
        }
    }
}
