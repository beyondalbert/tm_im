package com.tm.im.core.config;

import com.tm.im.common.id.IdGenerator;
import com.tm.im.common.id.SnowflakeIdGenerator;
import com.tm.im.core.agent.AgentProperties;
import com.tm.im.core.conversation.ConversationProperties;
import com.tm.im.core.friend.FriendProperties;
import com.tm.im.core.identity.JwtTokenService;
import com.tm.im.core.message.MessageProperties;
import com.tm.im.core.plaza.PlazaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.ZoneId;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
        FriendProperties.class, AgentProperties.class, PlazaProperties.class})
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

    /**
     * 写扩散（广场）的线程池 —— 发帖之后把动态写进好友的收件箱。
     *
     * <p><b>为什么是一个固定大小的有界线程池而不是 {@code newCachedThreadPool}</b>：
     * 扩散任务的代价是「作者好友数」次 INSERT，而一条被转发到几万人的动态
     * 能让缓存线程池开出几万个线程——那不是“更高的并发”，而是一次自杀。
     * 池子满了就丢弃（记 WARN）：发帖接口不该因为扩散队列的长度而变慢，
     * 而丢掉的那部分正好可以由读取路径（公开流）补上。
     *
     * <p>线程是 daemon：应用停机时它们不该拖住 JVM，{@code destroyMethod=shutdown}
     * 只是让“优雅停机”时任务有机会跑完当前那一批。
     */
    @Bean(name = "plazaFanoutExecutor", destroyMethod = "shutdown")
    public ExecutorService plazaFanoutExecutor(PlazaProperties properties) {
        int threads = Math.max(1, properties.getFanoutThreads());
        int queue = Math.max(1, properties.getFanoutQueueCapacity());
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "tm-fanout-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(threads, threads,
                0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queue), factory,
                (task, pool) -> log.warn("写扩散队列已满（tm.feed.fanout-queue-capacity={}），"
                        + "丢弃本次扩散任务：那条动态不会进好友的收件箱，"
                        + "但它仍会出现在作者个人页与公开流里", queue));
        log.info("广场写扩散线程池: threads={} queue={} 大V阈值={}",
                threads, queue, properties.getCelebrityThreshold());
        return executor;
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
