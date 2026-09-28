package com.tm.im.api.admin.config;

import com.tm.im.api.admin.auth.CurrentAdminArgumentResolver;
import com.tm.im.core.admin.AdminService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 后台 REST 的装配：注册 {@link CurrentAdminArgumentResolver}。
 *
 * <p>与用户端的 {@code ApiWebConfiguration} 长得几乎一样，但<b>是两份</b>：
 * 两边的解析器分别调 {@code IdentityService} 与 {@code AdminService}，
 * 而「共用一份 MVC 配置」意味着后台进程里必须能构造出用户端的身份服务——
 * 那正是 M9 要避免的依赖（见 tm-api-admin/pom.xml 的注释）。
 *
 * <p>这里同样没有 CORS 配置，理由与用户端一致：后台前端开发期用 Vite 的
 * {@code server.proxy} 转发，生产环境前端打进同一个 JAR（同源）。
 */
@Configuration
public class AdminWebConfiguration implements WebMvcConfigurer {

    private final AdminService admins;

    public AdminWebConfiguration(AdminService admins) {
        this.admins = admins;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentAdminArgumentResolver(admins));
    }
}
