package com.tm.im.api.user.view;

import com.tm.im.core.identity.AccountService;

/**
 * 一次凭证发放的响应体（02-auth.md §2.1 / §2.3）。
 *
 * <p>字段名在 JSON 里是 snake_case（{@code access_token}），由
 * {@code spring.jackson.property-naming-strategy: SNAKE_CASE} 统一转换——
 * 不逐字段写 {@code @JsonProperty}：那样每加一个字段都要记得加注解，
 * 而忘记的表现是「这个字段突然变成 camelCase」，与文档示例不一致，
 * 客户端解析失败。<b>一处配置 vs 每字段一次手写</b>，前者才对。
 *
 * <p>刷新接口也返回 {@code actor_id} / {@code handle}：§2.3 的示例里没有这两个字段，
 * 多返回属于「加字段」（协议演进允许，客户端忽略未知字段），
 * 而少返回一个客户端要用的字段没法补救。
 */
public record TokenView(long actorId, String handle, String accessToken, String refreshToken,
                        long expiresIn) {

    /** 唯一的转换点：避免每个控制器各拼一遍字段。 */
    public static TokenView of(AccountService.TokenPair pair) {
        return new TokenView(pair.actorId(), pair.handle(), pair.accessToken(),
                pair.refreshToken(), pair.expiresInSeconds());
    }
}
