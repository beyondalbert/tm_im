package com.tm.im.channel.session;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.util.concurrent.TimeUnit;

/**
 * Channel 上的属性键。集中定义避免各处 {@code AttributeKey.valueOf} 字符串漂移
 * （两个地方拼错一个字母，会得到两个互不相干的属性，且不报错）。
 */
public final class ChannelAttributes {

    /** 已鉴权连接的会话；{@code null} 表示尚未通过 AUTH。 */
    public static final AttributeKey<TmSession> SESSION = AttributeKey.valueOf("tm.session");

    /**
     * 握手状态。
     *
     * <p>单独用一个属性而不是「SESSION != null 就是已鉴权」，是因为中间存在
     * <b>正在鉴权</b>这个状态：鉴权要查库，必须离开 IO 线程（DESIGN §7.3），
     * 于是「收到 AUTH 帧」与「鉴权完成」之间有一个窗口。
     * 没有这个状态的话，超时任务会在鉴权进行中把连接掐掉 ——
     * 表现为「偶发鉴权失败」，且只在数据库慢的时候出现。
     */
    public static final AttributeKey<HandshakeState> HANDSHAKE = AttributeKey.valueOf("tm.handshake");

    /**
     * 握手总时限的绝对时刻（{@link System#nanoTime()} 口径）。
     *
     * <p>存「绝对时刻」而不是「剩余毫秒」是因为：{@code auth-timeout-ms} 的语义是
     * 「从连接建立到完成 AUTH 的总预算」。但连接建立的那一刻，协议还没分流，
     * 真正处理 AUTH 的 {@code AuthHandler} 尚未被装进 pipeline，它无法在那个时刻
     * 上闹钟。若让它「从收到第一个字节起再算一份独立预算」，总时长就会变成
     * 2 倍（实测过：先发一个字节再不发 AUTH，连接能活到 2×auth-timeout-ms），
     * 对外承诺的时限就变成了谎话。存绝对时刻后，两个阶段各自等待
     * 「离同一个死线还剩多久」，总预算严格等于配置值。
     */
    public static final AttributeKey<Long> HANDSHAKE_DEADLINE = AttributeKey.valueOf("tm.handshake.deadline");

    public enum HandshakeState {
        /** 连接已建立，等待第一帧 AUTH。 */
        AWAITING_AUTH,
        /** 已收到 AUTH，正在查库。此时不再有超时。 */
        AUTHENTICATING,
        /** 鉴权成功，连接进入就绪态。 */
        READY
    }

    private ChannelAttributes() {
    }

    /** 便捷读取：未初始化时返回 AWAITING_AUTH。 */
    public static HandshakeState state(Channel channel) {
        HandshakeState s = channel.attr(HANDSHAKE).get();
        return s == null ? HandshakeState.AWAITING_AUTH : s;
    }

    public static TmSession session(Channel channel) {
        return channel.attr(SESSION).get();
    }

    /** 在连接建立时记下握手死线（由第一个见到每条连接的处理器调用）。 */
    public static void armHandshakeDeadline(Channel channel, long timeoutMillis) {
        channel.attr(HANDSHAKE_DEADLINE)
                .set(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
    }

    /**
     * 离握手死线还剩多少纳秒（可能为负，调用方自行决定如何处理）。
     *
     * <p>没有登记过死线时回退为「从现在起再给 full timeout」：
     * 那种情况只出现在「本处理器被单独使用」的场景（测试、或将来的复用），
     * 此时按配置值计时比抛异常更合理。
     */
    public static long handshakeRemainingNanos(Channel channel, long fallbackTimeoutMillis) {
        Long deadline = channel.attr(HANDSHAKE_DEADLINE).get();
        if (deadline == null) {
            return TimeUnit.MILLISECONDS.toNanos(fallbackTimeoutMillis);
        }
        return deadline - System.nanoTime();
    }
}
