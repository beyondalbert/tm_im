package com.tm.im.channel.config;

import com.tm.im.channel.cluster.ActorRouteTable;
import com.tm.im.channel.cluster.ClusterAwareConnectionRegistry;
import com.tm.im.channel.cluster.NodeIdentity;
import com.tm.im.channel.cluster.NodeProperties;
import com.tm.im.channel.codec.Frames;
import com.tm.im.channel.registry.LocalConnectionRegistry;
import com.tm.im.channel.session.ConnectionRegistry;
import com.tm.im.common.error.ErrorCode;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 长连接装配。
 */
@Configuration
@EnableConfigurationProperties({NettyProperties.class, NodeProperties.class})
public class ChannelConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ChannelConfiguration.class);

    /**
     * 业务线程池（DESIGN §7.3）。
     *
     * <p><b>为什么用 {@code AbortPolicy} 而不是 {@code CallerRunsPolicy}</b>：
     * CallerRuns 是「池满时由提交者自己执行」，而提交者是 <b>IO 线程</b> ——
     * 那正好等于在 EventLoop 上跑 DB，一个慢查询就会把该线程上的所有连接一起拖住，
     * 而这正是本节铁律要禁止的事。Abort 则让超载表现为「这一帧被拒绝」，
     * IO 线程始终只做编解码与投递。对客户端的答复是 50004（可重试），
     * 语义明确；而 CallerRuns 造成的是全局延迟抖动，客户端只会看到「变慢」。
     *
     * <p>队列有界（{@code business-queue-capacity}）也是刻意的：无界队列会把
     * 「超载」从「快速失败」变成「内存耗尽 + 越来越长的延迟」。
     */
    @Bean(destroyMethod = "shutdownNow")
    public ThreadPoolExecutor nettyBusinessExecutor(NettyProperties properties) {
        ThreadFactory factory = namedFactory("tm-biz-");
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                properties.getBusinessThreads(),
                properties.getBusinessThreads(),
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.getBusinessQueueCapacity()),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
        // 允许核心线程超时回收：低峰期不必常驻 32 个线程。
        // 注意「允许 core 超时」与队列配合时表现正常，因为这里 core == max。
        executor.allowCoreThreadTimeOut(true);
        log.info("业务线程池已创建：threads={} queue={}",
                properties.getBusinessThreads(), properties.getBusinessQueueCapacity());
        return executor;
    }

    /**
     * 本节点身份（DESIGN §7.4）。
     *
     * <p>必须等 Netty 端口确定之后才能解析：{@code tm.netty.port=0} 表示
     * 「由系统分配端口」（测试与容器里常用），而按 0 推导出来的 nodeId
     * 会让所有节点撞成同一个 —— 那正是这个类要防的那种缺陷。
     * 这里用配置值而不是实际绑定值，是因为本 Bean 在 Netty 启动之前就要就绪
     * （心跳得先登记存活）：{@code port=0} 时请显式配置 {@code tm.node.id}。
     */
    @Bean
    public NodeIdentity nodeIdentity(NodeProperties properties, NettyProperties netty) {
        return properties.resolve(netty.getPort());
    }

    /**
     * 连接注册表：本地表 + 集群路由装饰。
     *
     * <p>容器里只暴露装饰后的这一个：本地实现自己不再声明为 Spring Bean
     * （否则按类型注入会有两个候选，启动即报歧义），这样「注册连接」与
     * 「发布路由」之间那条不变式就不可能被绕过 —— 见
     * {@link ClusterAwareConnectionRegistry}。
     */
    @Bean
    public ConnectionRegistry connectionRegistry(ActorRouteTable routeTable) {
        return new ClusterAwareConnectionRegistry(new LocalConnectionRegistry(), routeTable);
    }

    /**
     * 把业务任务提交到线程池；被拒绝时立刻回 ERROR 而不是静默丢弃。
     *
     * <p>单独抽出来是为了让「超载时客户端收到什么」这件事只有一处定义 ——
     * 否则每个 handler 都要写一遍 try/catch，早晚有一个漏掉，
     * 表现为「超载时某类请求静默超时」。
     */
    public static void submitBusiness(ThreadPoolExecutor executor, Channel channel, Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            log.warn("业务队列已满，拒绝并回 50004 channel={} queue={}",
                    channel.remoteAddress(), executor.getQueue().size());
            // 写操作在任意线程都是安全的（Netty 会投递到该 Channel 的 EventLoop）。
            channel.writeAndFlush(Frames.error(0, ErrorCode.SERVICE_OVERLOADED, "business queue full"));
        }
    }

    /** 线程名带前缀与序号：jstack 里一眼能看出是长连接的哪个线程。 */
    public static ThreadFactory namedFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
