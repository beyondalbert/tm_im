package com.tm.im.api.user.config;

import com.tm.im.api.user.auth.CurrentActorArgumentResolver;
import com.tm.im.core.identity.IdentityService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * REST 层装配。目前只有一件事：注册 {@link CurrentActorArgumentResolver}。
 *
 * <p><b>为什么这里没有 CORS 配置</b>：M3 的前端是独立的 Vite 开发服务器
 * （{@code :5173}），而 API 在 {@code :8080}，看着像是必须配 CORS。
 * 但 Vite 自带 {@code server.proxy}——把 {@code /v1} 转发到后端，
 * 浏览器看到的就是同源请求，<b>一个 CORS 头都不需要</b>。
 *
 * <p>选代理而不是 CORS 的理由不是省几行配置，而是省掉一个<b>只在浏览器里
 * 才会显形的配置面</b>：CORS 配错时 curl 全绿、只有浏览器红，而错误信息
 * （缺 {@code Access-Control-Allow-Origin}）不指向任何一行服务端代码。
 * 生产环境本来就同源（前端打进 JAR，DESIGN §5.2），所以代理这条路上
 * 开发与生产的请求形态是一致的；而 CORS 那条路上不一致。
 */
@Configuration
public class ApiWebConfiguration implements WebMvcConfigurer {

    private final IdentityService identity;

    public ApiWebConfiguration(IdentityService identity) {
        this.identity = identity;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentActorArgumentResolver(identity));
    }
}
