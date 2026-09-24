package com.tm.im.channel.server;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;

/**
 * 按「分流器之后、依次追加」的语义安装处理器。
 *
 * <p>为什么不用 {@code pipeline.addLast}：分流器当时可能<b>不是</b>最后一个处理器
 * （例如将来在它后面挂一个连接级限流器，或测试里加了探针），
 * 那时 addLast 会把业务处理器装到错误的顺序上——而顺序决定了编解码链能否成立
 * （入站按添加顺序、出站按相反顺序，见 {@code ChannelPipelineInitializer} 的注释）。
 *
 * <p>为什么不用「多个 addAfter(anchor, ...)」：每次 addAfter 都会插到锚点<b>紧后面</b>，
 * 连续插入同一锚点会让顺序颠倒。所以这里记录「上一个插入的名字」，
 * 逐个链式追加，顺序与调用顺序一致。
 */
public final class PipelineBuilder {

    private final ChannelPipeline pipeline;
    private String previous;

    public PipelineBuilder(ChannelPipeline pipeline, String anchorName) {
        this.pipeline = pipeline;
        this.previous = anchorName;
    }

    public PipelineBuilder add(String name, ChannelHandler handler) {
        pipeline.addAfter(previous, name, handler);
        previous = name;
        return this;
    }

    /**
     * 自动命名版本：名字取「类型名#identityHashCode」。
     *
     * <p>带类型名是为了在 {@code /actuator}/日志里能一眼看出这条连接上装了什么；
     * 加上 identityHashCode 是因为同名处理器不能在同一个 pipeline 里重复
     * （每个 pipeline 都装一份同名实例，Netty 会拒绝）。
     */
    public PipelineBuilder add(ChannelHandler handler) {
        String simple = handler.getClass().getSimpleName();
        String name = (simple.isEmpty() ? "handler" : simple) + "#" + System.identityHashCode(handler);
        return add(name, handler);
    }
}
