package com.tm.im.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 身份与令牌配置。
 *
 * <p><b>{@code jwt-secret} 刻意没有默认值</b>：默认值意味着所有部署共用一把钥匙，
 * 一处泄露（哪怕是测试环境）就能伪造任意账号的 token。缺失时启动直接失败，
 * 而不是退化成一个「能跑但没有安全性」的状态——后者往往会被带到生产。
 */
@ConfigurationProperties(prefix = "tm.identity")
public class IdentityProperties {

    /** HS256 签名密钥。至少 32 字节，由部署环境注入（环境变量 TM_JWT_SECRET）。 */
    private String jwtSecret;

    /**
     * access token 有效期，默认 2 小时（与 02-auth.md §2 的 {@code expires_in: 7200} 一致）。
     *
     * <p>这个值同时是「封禁生效的最坏延迟」（token 里不带权限，但带了身份；
     * 状态每次查库，所以真正受影响的是「已登录连接」——它靠 KICK 主动断开）。
     */
    private Duration accessTokenTtl = Duration.ofHours(2);

    /**
     * refresh token 有效期，默认 30 天。
     *
     * <p><b>这个值直接决定「一次登录能待多久」</b>：access token 只有 2 小时，
     * 但它每两小时就能用 refresh_token 自动续一次，所以实际的会话长度是
     * <b>这个值</b>，而不是 access token 的有效期。把它当作「安全参数」时需要
     * 同时看到两件事：客户端每 30 天至少要和用户交互一次（重新输口令），
     * 而封禁的最坏生效延迟是这个值<b>除以</b>刷新频率——只要刷新时查一次账号状态
     * （{@code AccountService.refresh} 就是这么做的），封禁的生效延迟就仍然是秒级。
     *
     * <p>它同时是 Redis 里 {@code tm:rt:*} 键的 TTL（见 RedisRefreshTokenStore），
     * 所以改大它意味着存活的会话记录更多。
     */
    private Duration refreshTokenTtl = Duration.ofDays(30);

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public Duration getRefreshTokenTtl() {
        return refreshTokenTtl;
    }

    public void setRefreshTokenTtl(Duration refreshTokenTtl) {
        this.refreshTokenTtl = refreshTokenTtl;
    }
}
