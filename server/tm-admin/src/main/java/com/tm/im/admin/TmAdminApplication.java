package com.tm.im.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * 管理后台入口（DESIGN §5.1：独立 JAR = Tomcat :8081，<b>没有长连接</b>）。
 *
 * <p><b>为什么是三个注解而不是 {@code @SpringBootApplication}</b>：与 {@code TmAppApplication}
 * 同一理由——{@code @SpringBootApplication} 把 {@code @ComponentScan} 的属性固定成
 * 「扫本类所在的包」，而这里的扫描范围是 M9 最要紧的一行配置，必须写在明面上。
 *
 * <h2>为什么是「按包列出」而不是「扫 com.tm.im 再排掉几个」</h2>
 *
 * <p>{@code tm-app} 用的是后者（扫 {@code com.tm.im}，排掉 {@code ...it...}），
 * 因为它要用到全部领域服务。后台恰恰相反：它只用到其中很小一部分，
 * 而 <b>opt-out 的失效方向是危险的</b>——{@code tm-core} 里新加一个 {@code @Service}，
 * 它会<b>静默地</b>进入管理进程；而 opt-in 的失效方向是「启动失败 + 一个缺 Bean 的报错」。
 * 前者可能要等到某次安全评审才发现，后者在 CI 里就会红。
 *
 * <h2>这一份清单本身就是「后台依赖什么」的文档</h2>
 *
 * <ul>
 *   <li>{@code com.tm.im.admin} —— 本应用（启动、bootstrap）；</li>
 *   <li>{@code com.tm.im.api.admin} —— {@code /v1/admin} 控制器；</li>
 *   <li>{@code com.tm.im.api.common} —— 与用户端共用的响应信封与异常翻译；</li>
 *   <li>{@code com.tm.im.core.config} —— {@code CoreConfiguration}（IdGenerator /
 *       ZoneId / 各属性类 / 写扩散线程池）；</li>
 *   <li>{@code com.tm.im.core.admin} —— {@code AdminService} 自己；</li>
 *   <li>{@code com.tm.im.core.plaza} + {@code com.tm.im.core.media} —— 审核删帖要把
 *       收件箱/点赞/评论一起清（{@code PlazaService#deleteAsAdmin}），
 *       而 Plaza 依赖媒体服务做图片归属校验；</li>
 *   <li>{@code com.tm.im.storage} —— 仓储实现与 Mapper（含 {@code StorageConfiguration}）。</li>
 * </ul>
 *
 * <p><b>刻意不在清单里的包，以及为什么</b>：
 * {@code com.tm.im.core.identity}（JWT / 账号体系）、{@code com.tm.im.core.message}、
 * {@code com.tm.im.core.conversation}、{@code com.tm.im.core.friend}、
 * {@code com.tm.im.api.user}。它们带来的直接好处是<b>后台进程不需要
 * {@code TM_JWT_SECRET}</b>：那把钥匙能伪造任意用户的 token，让它出现在
 * 「能封任何人号」的进程里没有任何必要。同样地，后台也不需要长连接那一整套解析。
 *
 * <p>反过来说，{@code tm-core} 里新加一个领域服务时，它的包要不要进这份清单
 * 是一个<b>必须有人回答</b>的问题——这正是把它写在这里的目的。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = {
        "com.tm.im.admin",
        "com.tm.im.api.admin",
        // 统一响应信封与异常翻译（ApiExceptionHandler）。少扫这一个包的后果极其隐蔽：
        // 路由是通的，但失败响应会退回 Spring 默认的那份 {"timestamp":...,"error":"..."}
        // ——即「没有 code 字段」，于是所有 assertThat(body.get("code")) 都拿到 null。
        "com.tm.im.api.common",
        "com.tm.im.core.config",
        "com.tm.im.core.admin",
        "com.tm.im.core.plaza",
        "com.tm.im.core.media",
        "com.tm.im.storage"
})
public class TmAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(TmAdminApplication.class, args);
    }
}
