package com.tm.im.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * 用户端应用入口（DESIGN §5.1：一个 JAR = Tomcat :8080 + Netty :8090）。
 *
 * <p><b>为什么是三个注解而不是 {@code @SpringBootApplication}</b>：
 * {@code @SpringBootApplication} 展开后正是这三者（{@code @SpringBootConfiguration}
 * + {@code @EnableAutoConfiguration} + {@code @ComponentScan}），但它把
 * {@code @ComponentScan} 的属性固定成了「扫本类所在的包」，加过滤条件要么
 * 与之并列（注解合并的语义不直观），要么换 `scanBasePackages`。
 * 这里直接写开，让「扫哪里」与「不扫哪里」两件事都在同一个地方看见。
 *
 * <p><b>扫描根必须显式写 {@code com.tm.im}</b>：默认只扫 {@code com.tm.im.app}，
 * 而所有组件都在 {@code com.tm.im.*} 下。不写这一句的后果不是报错，而是
 * <b>一个启动成功但少了一半 Bean 的应用</b>——Netty 端口没人监听、
 * 仓储找不到，表现却是「某个接口 404」，很难联想到「启动类放在哪」。
 *
 * <p><b>为什么要把 {@code ...it...} 包排除掉</b>：{@code tm-storage} 的集成测试
 * 支撑类（{@code ItSpringConfig} / {@code RedisItConfig}）会以 test-jar 形式
 * 出现在本模块的测试 classpath 上（{@code AppHttpIT} 需要 {@code ItEnv} 里的
 * 真实服务坐标）。它们是 {@code @Configuration}，而扫描根宽到 {@code com.tm.im}，
 * 于是<b>测试用的容器配置会被装进生产容器</b>：
 * <pre>
 * NoUniqueBeanDefinitionException: expected single matching bean but found 2:
 *   conversationRepositoryImpl, conversationRepositoryWithoutRedis
 * </pre>
 * 这个 {@code conversationRepositoryWithoutRedis} 是给「Redis 不可用」那条
 * 降级分支做集成测试用的——生产容器里多出它，一来按类型注入直接歧义启动失败，
 * 二来就算绕过去，装配出来的也不是生产的那套。
 *
 * <p>排除规则按包的形状（{@code com.tm.im.<模块>....it.<类>}）而不是逐个类名：
 * 逐个列举会在下一次有人往 test-jar 里加一个 {@code @Configuration} 时失效，
 * 而失效的表现是「应用起不来」，与「我加了个测试配置」毫无因果关系。
 * 本项目的约定是「测试支撑类放在模块的 {@code it} 子包」——这条约定现在有了
 * 一个机器后果，而不再只是风格建议。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = "com.tm.im",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.tm\\.im\\..*\\.it\\..*"))
public class TmAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(TmAppApplication.class, args);
    }
}
