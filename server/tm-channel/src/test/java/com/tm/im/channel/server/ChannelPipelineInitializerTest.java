package com.tm.im.channel.server;

import com.tm.im.channel.config.ChannelConfiguration;
import com.tm.im.channel.config.NettyProperties;
import com.tm.im.channel.registry.LocalConnectionRegistry;
import com.tm.im.channel.support.InMemoryIdentity;
import com.tm.im.channel.support.InMemoryMessagePort;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配层的两条不变式。
 *
 * <p>这个测试是「真连接测试抓到一个致命缺陷」之后补上的
 * （{@code ConnectionLimiter} 未标 {@code @Sharable}，导致第 2 条连接
 * 在 {@code initChannel} 阶段就被关闭）：单元测试<b>逐条</b>测了每个处理器，
 * 却没有任何一条测「同一个 initializer 被用于多条连接」这件事。
 * 而真实的 initializer 恰恰是<b>每实例一个、被所有连接共用</b>的。
 *
 * <p>因此这里测的不是行为，而是装配期的结构性约束：
 * <ol>
 *   <li>同一个 {@link ChannelPipelineInitializer} 连续初始化多条连接不能抛异常；</li>
 *   <li>任何处理器实例都不得在两条 pipeline 中出现 —— 除非它标了
 *       {@code @Sharable}（真正无单连接状态）。这条不变式正是上面那个缺陷的抽象。</li>
 * </ol>
 */
class ChannelPipelineInitializerTest {

    private static ThreadPoolExecutor executor;
    private static TestableInitializer initializer;

    @BeforeAll
    static void setUp() {
        NettyProperties properties = new NettyProperties();
        properties.setBusinessThreads(1);
        executor = new ChannelConfiguration().nettyBusinessExecutor(properties);

        InMemoryIdentity identity = new InMemoryIdentity("initializer-test-secret-at-least-32-bytes");
        initializer = new TestableInitializer(properties, identity.service(),
                new LocalConnectionRegistry(), executor, new ConnectionLimiter(10),
                new InMemoryMessagePort(), java.time.ZoneId.of("Asia/Shanghai"));
    }

    @AfterAll
    static void tearDown() throws Exception {
        executor.shutdownNow();
        executor.awaitTermination(3, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("同一个 initializer 被多条连接共用：不能有任何处理器实例被重复加入")
    void handlerInstancesAreNotSharedAcrossChannels() {
        List<SocketChannel> channels = List.of(openChannel(), openChannel(), openChannel());

        Set<ChannelHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (SocketChannel channel : channels) {
            // toMap() 比 names() 好用：后者会把 Netty 自己的 TailContext 也列出来，
            // 而那个名字在 get(name) 里查不到（返回 null）—— 只按 names() 遍历会踩到。
            channel.pipeline().toMap().forEach((name, handler) -> {
                if (handler instanceof ChannelHandlerAdapter adapter && adapter.isSharable()) {
                    return;
                }
                assertThat(seen.add(handler))
                        .as("处理器 %s (%s) 被两条连接共用，但它不是 @Sharable —— "
                                        + "Netty 会在 initChannel 阶段直接关掉第二条连接",
                                name, handler.getClass().getSimpleName())
                        .isTrue();
            });
        }
    }

    @Test
    @DisplayName("分流前的 pipeline 只包含连接数上限与分流器（其余处理器由分流结果决定）")
    void sniffingStageHasExactlyTwoHandlers() {
        SocketChannel channel = openChannel();

        // 顺序有语义：连接数上限在最前，超限的连接连「第一字节」都不必读。
        assertThat(channel.pipeline().toMap().keySet())
                .containsExactly("conn-limit", "protocol-sniffer");
        assertThat(channel.pipeline().get("conn-limit"))
                .isInstanceOf(ConnectionLimiter.class)
                .isInstanceOfSatisfying(ChannelHandlerAdapter.class,
                        adapter -> assertThat(adapter.isSharable())
                                .as("同一个实例对所有连接共享，必须声明为可共享")
                                .isTrue());
    }

    /** 通过真实 socket channel 走一遍 {@code initChannel}（就是 {@code handlerAdded} 的路径）。 */
    private static SocketChannel openChannel() {
        SocketChannel channel = new NioSocketChannel();
        initializer.initFor(channel);
        return channel;
    }

    /** {@code initChannel(SocketChannel)} 是 protected：用子类把入口暴露出来给测试。 */
    private static final class TestableInitializer extends ChannelPipelineInitializer {

        TestableInitializer(NettyProperties properties,
                            com.tm.im.core.identity.IdentityService identityService,
                            com.tm.im.channel.session.ConnectionRegistry registry,
                            ThreadPoolExecutor businessExecutor,
                            ConnectionLimiter limiter,
                            com.tm.im.core.message.MessageCommandPort messages,
                            java.time.ZoneId databaseZone) {
            super(properties, identityService, registry, businessExecutor, limiter,
                    messages, databaseZone);
        }

        void initFor(SocketChannel channel) {
            initChannel(channel);
        }
    }
}
