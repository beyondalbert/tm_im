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
}
