package com.tm.im.core.config;

import com.tm.im.common.id.IdGenerator;
import com.tm.im.common.id.SnowflakeIdGenerator;
import com.tm.im.core.agent.AgentProperties;
import com.tm.im.core.conversation.ConversationProperties;
import com.tm.im.core.friend.FriendProperties;
import com.tm.im.core.identity.JwtTokenService;
import com.tm.im.core.message.MessageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.ZoneId;

/**
 * 领域核心装配。
 *
 * <p>{@code @EnableConfigurationProperties} 而不是给属性类加 {@code @Component}：
 * 属性类一旦是 Bean，就可以被任意代码 {@code @Autowired} 进来「顺手读一下」，
 * 于是配置项会慢慢渗进业务逻辑（例如在 MessageService 里直接读 jwtSecret）。
 * 这里把「读配置」集中在装配点，业务类只能通过构造参数拿到<b>已经建好的对象</b>。
 */
@Configuration
@EnableConfigurationProperties({SnowflakeProperties.class, IdentityProperties.class,
        TimeProperties.class, MessageProperties.class, ConversationProperties.class,
        FriendProperties.class, AgentProperties.class})
public class CoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CoreConfiguration.class);

    @Bean
    public IdGenerator idGenerator(SnowflakeProperties properties) {
        // 主机名作为未配置时的哈希种子：容器/主机名在部署系统里天然唯一且有语义，
        // 比随机数好（随机数在每次重启后会换节点号，跨重启的 ID 会交错）。
        return new SnowflakeIdGenerator(properties.nodeId(hostname()));
    }

    @Bean
    public JwtTokenService jwtTokenService(IdentityProperties properties) {
        return new JwtTokenService(properties.getJwtSecret());
    }

    /**
     * 库里 DATETIME 所代表的时区。
     *
     * <p>暴露为 Bean 而不是让各处自己读配置：这个口径必须全局唯一 ——
     * 若某一处用 UTC、另一处用东八区，表现是「同一个时间在两个接口里差 8 小时」。
     *
     * <p>启动时打印一次实际取值，便于与 sharding.yaml 的 serverTimezone 比对。
     */
    @Bean
    public ZoneId databaseZoneId(TimeProperties properties) {
        log.info("数据库时间口径 tm.time.zone={}（必须与 sharding.yaml 中 jdbcUrl 的 serverTimezone 一致）",
                properties.getZone());
        return properties.getZone();
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            // 拿不到主机名时不能抛：那会让「本机没配 hosts」变成启动失败。
            // 退化到常量会让所有此类实例撞到同一个 nodeId，所以这里只提示，
            // 由 SnowflakeProperties 的 WARN 提醒运维显式配置。
            return "unknown-host";
        }
    }
}
