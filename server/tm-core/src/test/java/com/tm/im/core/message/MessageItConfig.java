package com.tm.im.core.message;

import com.tm.im.common.id.IdGenerator;
import com.tm.im.common.id.SnowflakeIdGenerator;
import com.tm.im.core.channel.MessagePushPort;
import com.tm.im.storage.it.ItSpringConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.ZoneId;

/**
 * MessageService 的集成测试容器：在 {@link ItSpringConfig}（真实 MySQL + 真实 Redis）
 * 之上补上 tm-core 侧的装配。
 *
 * <p>刻意 {@code @Import} 而不是自己再写一份数据源配置：那套接法（ShardingSphere 驱动 +
 * HikariCP + MyBatis-Plus + Lettuce）只应该有一份，两份必然漂移——
 * 而「测试用的接法与生产不一致」是最难发现的偏差（测试全绿、生产连不上）。
 *
 * <p>只能替身化的部分是推送口：真实实现要连 Netty 注册表，
 * 而本类要验证的是「扇出决策」（推给谁、推几次），不是「帧能不能写进 Channel」。
 * 后者由 tm-channel 的端到端测试覆盖。
 */
@Configuration
@Import(ItSpringConfig.class)
public class MessageItConfig {

    @Bean
    public MessageProperties messageProperties() {
        return new MessageProperties();
    }

    /**
     * 固定节点号：集成测试要断言具体的 id 行为（比如「落库后返回的 id 就是本次生成的」），
     * 而雪花在真实实现里依赖时钟，节点号再随机就无法复现。
     */
    @Bean
    public IdGenerator idGenerator() {
        return new SnowflakeIdGenerator(7);
    }

    /**
     * 库内 DATETIME 的时区口径。生产由 {@code CoreConfiguration} 从
     * {@code tm.time.zone} 提供（并与 sharding.yaml 的 serverTimezone 对齐）；
     * 这里用同一个值，否则断言 created_at 时会差 8 小时。
     */
    @Bean
    public ZoneId databaseZoneId() {
        return ZoneId.of("Asia/Shanghai");
    }

    @Bean
    public RecordingPushPort messagePushPort() {
        return new RecordingPushPort();
    }

    /**
     * 参数上的 {@code @Qualifier} 不是多余的：容器里有两个 ConversationRepository
     * ——生产那个（走 Redis）与「无 Redis」那个（复现兜底路径）。
     * 业务代码显然要用前者，而按类型注入会因为两个候选直接启动失败。
     */
    @Bean
    public MessageService messageService(
            @Qualifier("conversationRepositoryImpl")
            com.tm.im.domain.repository.ConversationRepository conversations,
            com.tm.im.domain.repository.MessageRepository messages,
            com.tm.im.domain.repository.FriendshipRepository friendships,
            MessagePushPort push,
            IdGenerator idGenerator,
            MessageProperties properties,
            ZoneId databaseZoneId) {
        return new MessageService(conversations, messages, friendships, push,
                idGenerator, properties, databaseZoneId);
    }
}
