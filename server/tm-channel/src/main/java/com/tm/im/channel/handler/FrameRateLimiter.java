package com.tm.im.channel.handler;

import com.tm.im.channel.codec.Frames;
import com.tm.im.common.error.ErrorCode;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * 单连接帧率限制（配置项 {@code tm.netty.max-frames-per-second}）。
 *
 * <p>放在<b>解码之后、鉴权之前</b>，位置是刻意的：鉴权会查库，
 * 未鉴权连接如果不受限，就是一条「用 AUTH 帧打数据库」的免费放大器。
 * 放在鉴权之后等于把攻击面留在门外。
 *
 * <p>算法是标准令牌桶：桶容量 = 每秒配额（允许 1 秒的突发），按时间线性补充。
 * 超限时回 42901（可重试，见 07-errors-limits.md）并断开 ——
 * 只回错误不断开的话，恶意客户端可以继续以「刚好不超限」的速率占用连接；
 * 只断开不回错误的话，正常客户端偶发突发会表现为「莫名其妙掉线」，无法归因。
 */
public class FrameRateLimiter extends ChannelInboundHandlerAdapter {

    private final int framesPerSecond;
    /** 桶内令牌数，允许小数以便按纳秒线性补充。 */
    private double tokens;
    private long lastRefillNanos;

    /** 累计被拒帧数，仅用于日志定位（每条连接一个实例）。 */
    private long rejected;

    public FrameRateLimiter(int framesPerSecond) {
        if (framesPerSecond <= 0) {
            throw new IllegalArgumentException("framesPerSecond 必须为正，实际 " + framesPerSecond);
        }
        this.framesPerSecond = framesPerSecond;
        this.tokens = framesPerSecond;
        this.lastRefillNanos = System.nanoTime();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (consumeToken()) {
            ctx.fireChannelRead(msg);
            return;
        }
        rejected++;
        ctx.writeAndFlush(Frames.error(0, ErrorCode.RATE_LIMIT_EXCEEDED,
                        rejected + " frames rejected (limit " + framesPerSecond + "/s)"))
                .addListener(io.netty.channel.ChannelFutureListener.CLOSE);
    }

    private boolean consumeToken() {
        long now = System.nanoTime();
        // 按经过的时间补充令牌，上限为桶容量（1 秒配额）。
        double refill = (now - lastRefillNanos) / 1_000_000_000.0 * framesPerSecond;
        lastRefillNanos = now;
        tokens = Math.min(framesPerSecond, tokens + refill);
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    /** 供运维观测：这条连接被拒了多少帧。 */
    public long rejected() {
        return rejected;
    }

    /** 便于测试与排障：当前令牌数（小数表示正在积累）。 */
    double availableTokens() {
        return tokens;
    }
}
