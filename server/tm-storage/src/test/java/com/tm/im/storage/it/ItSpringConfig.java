package com.tm.im.storage.it;

import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.tm.im.storage.mapper.ConversationMapper;
import com.tm.im.storage.mapper.ConversationMemberMapper;
import com.tm.im.storage.repository.ConversationRepositoryImpl;
import com.tm.im.storage.repository.ConversationSeqCounter;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 集成测试用的最小 Spring 容器：<b>真实 MySQL + 真实 Redis，但用生产的那些类</b>。
 *
 * <p><b>为什么不直接 new 出仓储对象</b>：{@code @Transactional} 靠 Spring 代理生效。
 * 手工 new 一个 {@code ConversationSeqCounter} 再调用它的方法，拿到的是
 * 「没有事务的版本」——于是测试验证的是另一段代码，而真正的缺陷
 * （自调用绕过代理 → 行锁提前释放 → 并发取到同一个序号）恰好会被这层假象盖住。
 * 所以容器可以很小，但事务代理必须是真的。
 *
 * <p><b>为什么数据源用 ShardingSphere 驱动而不是直连 MySQL</b>：
 * 与生产同构（{@code application-external.yml.example} 就是这么配的）。
 * 顺带保证「逻辑表名 message 由 ShardingSphere 路由」这条路径在集成测试里也被走过，
 * 而不是测试直连、生产走分片这种最难发现的偏差。
 *
 * <p><b>为什么仓储用组件扫描而不是一个个 {@code @Bean}</b>：手写清单会随新增仓储而
 * 漂移，而漂移的表现是「集成测试里某个 Bean 找不到」——那时才会有人去补一行。
 * 扫描则保证容器与生产装配一致（仓储都在 {@code com.tm.im.storage.repository} 下，
 * 依赖只有 Mapper 与 Redis 模板，这里都已提供）。
 *
 * <p>Redis 那部分装配在 {@link RedisItConfig}（本类 {@code @Import} 它）：
 * 只依赖 Redis 的集成测试（如 tm-channel 的 {@code ClusterRedisIT}）直接用它就够了，
 * 不必为了几条 Redis 断言先把 ShardingSphere + MySQL 拉起来。
 */
@Configuration
@EnableTransactionManagement
@ComponentScan(basePackageClasses = ConversationRepositoryImpl.class)
@MapperScan(basePackageClasses = ConversationMapper.class)
@Import(RedisItConfig.class)
public class ItSpringConfig {

    /** 走分片驱动；连接池开小一点，测试不需要几十条连接。 */
    @Bean
    public HikariDataSource dataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("it-pool");
        ds.setDriverClassName("org.apache.shardingsphere.driver.ShardingSphereDriver");
        ds.setJdbcUrl(ItEnv.shardingJdbcUrl());
        ds.setMaximumPoolSize(8);
        // 拿不到连接时快点失败：挂 30 秒再报错会把「配置错」拖成「测试超时」
        ds.setConnectionTimeout(10_000);
        return ds;
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory(HikariDataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTypeAliasesPackage("com.tm.im.domain.entity");
        // 必须用 MyBatis-Plus 的 factory bean：仓储实现里用的是
        // Wrappers.lambdaQuery(...)，它依赖 MP 在注册 Mapper 时建立的
        // 实体-列名缓存（TableInfoHelper）。换成原生 MyBatis 的 SqlSessionFactory，
        // 会以 "can not find lambda cache for this entity" 的形式失败。
        return factory.getObject();
    }

    @Bean
    public PlatformTransactionManager transactionManager(HikariDataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /**
     * 兜底路径：Redis 不可用（构造参数为 null）。
     *
     * <p>这不是「测试专用开关」，而是生产里真会发生的一种状态——Redis 连接抖动时
     * {@code nextSeq} 会退到数据库计数器。单独一个 bean 是为了能稳定地复现那条分支，
     * 而不是等它在某个深夜自己发生。
     */
    @Bean
    public ConversationRepositoryImpl conversationRepositoryWithoutRedis(
            ConversationMapper conversationMapper,
            ConversationMemberMapper memberMapper,
            ConversationSeqCounter seqCounter) {
        return new ConversationRepositoryImpl(conversationMapper, memberMapper, seqCounter);
    }
}
