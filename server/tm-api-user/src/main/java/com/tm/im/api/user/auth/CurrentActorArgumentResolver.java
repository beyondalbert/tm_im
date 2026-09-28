package com.tm.im.api.user.auth;

import com.tm.im.api.common.auth.BearerCredential;
import com.tm.im.core.identity.AuthContext;
import com.tm.im.core.identity.IdentityService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 把 {@code Authorization} 头变成方法参数上的 {@link AuthContext}。
 *
 * <p><b>唯一的鉴权入口</b>：控制器里拿不到「未鉴权的请求」——只要参数上有
 * {@link CurrentActor}，Spring 就必须先跑完这里，而这里失败会抛
 * {@link com.tm.im.common.error.TmException}，由
 * {@code ApiExceptionHandler} 翻译成 401/403。控制器代码里因此
 * 不存在「忘了判断是否登录」这种 bug，也没有第二种判断方式。
 *
 * <p><b>设备标识从哪来</b>：可选的 {@code X-TM-Device-Id} 头（见 {@link DeviceId}）。
 * 长连接那边它来自 AUTH 帧的 {@code device_id} 字段，REST 没有帧可以放，
 * 所以约定一个头。它<b>不参与</b>鉴权（见
 * {@link com.tm.im.core.identity.RefreshSession}），只是让日志能回答
 * 「这个人同时在几个设备上」。
 */
public class CurrentActorArgumentResolver implements HandlerMethodArgumentResolver {

    private final IdentityService identity;

    public CurrentActorArgumentResolver(IdentityService identity) {
        this.identity = identity;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentActor.class)
                && AuthContext.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        HttpServletRequest http = request.getNativeRequest(HttpServletRequest.class);
        if (http == null) {
            // 只会在「非 Servlet 环境复用了这套 MVC 装配」时发生（比如 WebFlux）。
            // 静默返回 null 会让控制器拿到空身份，所以这里直接抛。
            throw new IllegalStateException(
                    "CurrentActor 只能在 Servlet 请求里解析，当前环境没有 HttpServletRequest");
        }
        String credential = BearerCredential.require(http.getHeader(BearerCredential.HEADER));
        return identity.authenticate(credential, DeviceId.from(http.getHeader(DeviceId.HEADER)));
    }
}
