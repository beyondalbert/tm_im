package com.tm.im.api.admin.auth;

import com.tm.im.api.common.auth.BearerCredential;
import com.tm.im.core.admin.AdminContext;
import com.tm.im.core.admin.AdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 把 {@code Authorization: Bearer adm_…} 变成方法参数上的 {@link AdminContext}。
 *
 * <p><b>后台的唯一鉴权入口</b>：控制器里拿不到「未鉴权的请求」——只要参数上有
 * {@link CurrentAdmin}，Spring 就必须先跑完这里，而这里失败会抛
 * {@code TmException}，由共用的 {@code ApiExceptionHandler} 翻成 401/403。
 * 因此「这个接口忘了判权限」在后台这套代码里是写不出来的。
 *
 * <p><b>为什么复用 {@code BearerCredential}</b>：请求头格式与用户端完全一致
 * （都是 {@code Bearer}），而凭证类型由前缀区分。让后台换一个头名
 * （例如 {@code X-Admin-Token}）不会提高安全性——凭证的强度与传输方式
 * 才决定安全性，而头名只是一个约定；多一个约定就多一处要同步的地方。
 */
public class CurrentAdminArgumentResolver implements HandlerMethodArgumentResolver {

    private final AdminService admins;

    public CurrentAdminArgumentResolver(AdminService admins) {
        this.admins = admins;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentAdmin.class)
                && AdminContext.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        HttpServletRequest http = request.getNativeRequest(HttpServletRequest.class);
        if (http == null) {
            throw new IllegalStateException(
                    "CurrentAdmin 只能在 Servlet 请求里解析，当前环境没有 HttpServletRequest");
        }
        String credential = BearerCredential.require(http.getHeader(BearerCredential.HEADER));
        return admins.authenticate(credential, ClientIps.of(http));
    }
}
