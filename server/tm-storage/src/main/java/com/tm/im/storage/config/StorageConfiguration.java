package com.tm.im.storage.config;

import com.tm.im.storage.media.StorageProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfiguration {

    /*
     * 关于 @EnableConfigurationProperties(StorageProperties.class) 为什么在这里：
     *
     * 那一组配置项（tm.storage.*）描述的是「字节往哪存」，属于基础设施，与
     * @MapperScan 一样是「存储层的装配」。把它注册在 tm-core 的 CoreConfiguration 里
     * 也能跑通（tm-core 依赖 tm-storage），但那样 tm-core 就成了「媒体存储配置的
     * 拥有者」——它并不知道 /v1/media 用的根目录在哪，也不该知道。
     *
     * 代价是启动期校验（LocalFsMediaStore.init）也留在了这一层，那正是我们要的：
     * 「配了 oss 但没实现」这件事应当在任何用户请求之前失败。
     */
}
