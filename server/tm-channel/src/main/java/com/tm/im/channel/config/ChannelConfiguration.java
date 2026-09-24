package com.tm.im.channel.config;

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
@EnableConfigurationProperties(NettyProperties.class)
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

    /** 供鉴权与推送路径复用。 */
    @Bean
    public ConnectionRegistry connectionRegistry() {
        return new LocalConnectionRegistry();
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
