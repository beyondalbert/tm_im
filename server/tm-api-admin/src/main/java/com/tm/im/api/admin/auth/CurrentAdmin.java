package com.tm.im.api.admin.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个 {@code AdminContext} 参数为「当前后台身份」。
 *
 * <p>与用户端的 {@code @CurrentActor} 是<b>两个注解</b>，即使它们此刻的实现
 * 只差一行（一个调 {@code IdentityService}、一个调 {@code AdminService}）。
 * 合成的后果很具体：某个接口的「我是谁」会由两个不同的认证体系里
 * <b>先跑完的那个</b>决定，而两者成功时的类型还不同（一个 ActorContext、
 * 一个 AdminContext）——那种接口的鉴权行为只能靠读装配代码才知道。
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentAdmin {
}
