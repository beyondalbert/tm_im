package com.tm.im.storage.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 存储层装配。
 *
 * <p>{@code @MapperScan} 是必需的：Mapper 接口分布在 {@code com.tm.im.storage.mapper}，
 * 而 Spring Boot 的 MyBatis 自动配置只从<b>启动类所在包</b>向下扫描。
 * 不加这一行，症状是启动时报
 * {@code NoSuchBeanDefinitionException: ...Mapper}，而 Mapper 文件明明就在那儿。
 *
 * <p>枚举与字段映射的配置刻意<b>放在 application.yml 里而不是这里写 Java</b>：
 * <pre>
 * mybatis-plus:
 *   configuration:
 *     default-enum-type-handler: com.baomidou.mybatisplus.core.handlers.MybatisEnumTypeHandler
 *     map-underscore-to-camel-case: true
 * </pre>
 * 理由是 {@code MybatisEnumTypeHandler} 的存在与否决定了「枚举落库是数字还是字符串」，
 * 这是一个部署期就应当一眼可查的开关。藏在 Java 里，出问题时得先翻代码才知道当前是什么状态。
 */
@Configuration
@MapperScan("com.tm.im.storage.mapper")
public class StorageConfiguration {
}
