package com.tm.im.storage.it;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 集成测试的 Redis 坐标（真实实例，凭据来自 {@link ItEnv}）。
 *
 * <p><b>为什么从 {@code ItSpringConfig} 里拆出来</b>：不是所有集成测试都需要数据库。
 * 集群路由（{@code tm:route:* / tm:node:*}，见 {@code tm-channel} 的
 * {@code ClusterRedisIT}）只依赖 Redis，而 {@code ItSpringConfig} 还会拉起
 * ShardingSphere 数据源 —— 那意味着一个「只验 Redis 语义」的测试必须先有可用的
 * MySQL，否则红。红灯的原因指向环境而不是代码，正是要避免的那类失败。
 *
 * <p>拆出来但<b>不复制</b>：连接参数、口令、库号这三件事在任何一份副本里迟早会漂移，
 * 而漂移的表现是「某个测试连到了另一个库」——它照样全绿，却在验证别的东西。
 */
@Configuration
public class RedisItConfig {

    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(
                ItEnv.get("redis.host"), ItEnv.getInt("redis.port"));
        cfg.setDatabase(ItEnv.getInt("redis.db"));
        String pwd = ItEnv.getOrEmpty("redis.password");
        if (!pwd.isEmpty()) {
            cfg.setPassword(RedisPassword.of(pwd));
        }
        return new LettuceConnectionFactory(cfg);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }
}
