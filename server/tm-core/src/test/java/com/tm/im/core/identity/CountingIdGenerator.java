package com.tm.im.core.identity;

import com.tm.im.common.id.IdGenerator;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 确定性地吐 ID 的 {@link IdGenerator}。
 *
 * <p>{@code IdGenerator} 在 {@code tm-common} 里被抽出来时就写明了理由：
 * 「抽出来不是为了将来可能换实现，而是为了<b>可测</b>」——真实 Snowflake
 * 每次返回不同的值，测试就没法断言「插入的就是这一个」。这里兑现那句话说的事。
 */
class CountingIdGenerator implements IdGenerator {

    /** 起点取一个「看起来像 Snowflake 但又明显是测试数据」的值。 */
    static final long START = 1_000_000L;

    private final AtomicLong next = new AtomicLong(START);

    @Override
    public long nextId() {
        return next.incrementAndGet();
    }

    @Override
    public int nodeId() {
        return 1;
    }
}
