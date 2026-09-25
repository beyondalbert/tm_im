package com.tm.im.api.user.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记「这个参数要的是当前调用者」，由 {@link CurrentActorArgumentResolver} 注入。
 *
 * <p><b>为什么用参数注解而不是 {@code @RequestHeader} + 手写解析</b>：
 * 前者是「每一处都写一遍怎么从请求里取凭证」，后者是「只有一处」。
 * 差别不在代码量，而在<b>遗漏的后果</b>：漏掉一处的表现是一个不需要鉴权的接口——
 * 它照样能跑通、照样有测试，只是任何人都能读它。这类缺陷不会被任何
 * 「功能测试」发现，因为功能是好的。
 *
 * <p>拿到的 {@code AuthContext} 与长连接 AUTH 帧之后得到的是同一个类型
 * （{@code IdentityService.authenticate} 是两者唯一的入口），
 * 因此「同一个人的 REST 身份与长连接身份不一致」这件事在结构上不可能发生。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentActor {
}
