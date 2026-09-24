package com.tm.im.channel.session;

import com.tm.im.channel.codec.Frames;
import com.tm.im.proto.transport.Frame;
import io.netty.channel.Channel;

import java.util.Objects;

/**
 * 一条连接：{@link Channel} 与已鉴权会话{@link TmSession}的绑定。
 *
 * <p>为什么需要这个绑定对象，而不是在注册表里存 {@code Channel} 然后
 * 到处 {@code channel.attr(SESSION).get()}：会话信息在推送路径上每次都要用
 * （日志要打 actorId、背压要判断帧类型），从 attribute 里反复取既啰嗦又
 * 容易出现「这里忘了判 null」。绑定后，「未鉴权的连接不会进入注册表」
 * 这件事由类型保证。
 */
public final class TmConnection {

    private final Channel channel;
    private final TmSession session;

    public TmConnection(Channel channel, TmSession session) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.session = Objects.requireNonNull(session, "session");
    }

    public Channel channel() {
        return channel;
    }

    public TmSession session() {
        return session;
    }

    public long actorId() {
        return session.actorId();
    }

    public String remoteAddress() {
        return session.remoteAddress();
    }

    /**
     * 尝试写入一帧。
     *
     * <p>两处关键行为：
     * <ol>
     *   <li><b>背压丢弃</b>（DESIGN §7.6）：写缓冲超水位时 {@code isWritable()==false}，
     *       此时只丢「可丢帧」（PUSH），控制帧照写。判据在
     *       {@link Frames#droppableUnderBackpressure}。</li>
     *   <li><b>从任意线程调用是安全的</b>：{@code Channel.writeAndFlush} 在非 EventLoop
     *       线程上会自己投递过去（Channel 本身非线程安全，但这个方法做了处理）。
     *       这点很关键——推送会从 REST 线程、定时任务线程发起。</li>
     * </ol>
     *
     * @return true 已交给 Netty（不代表对端已收到）；false 表示被丢弃或连接已断
     */
    public boolean tryWrite(Frame frame) {
        if (!channel.isActive()) {
            return false;
        }
        if (!channel.isWritable() && Frames.droppableUnderBackpressure(frame.getCmd())) {
            return false;
        }
        channel.writeAndFlush(frame);
        return true;
    }

    @Override
    public String toString() {
        return "TmConnection{actorId=" + actorId() + ", from=" + remoteAddress() + "}";
    }
}
