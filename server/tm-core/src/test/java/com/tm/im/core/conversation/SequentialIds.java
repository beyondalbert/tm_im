package com.tm.im.core.conversation;

import com.tm.im.common.id.IdGenerator;

/** 递增的假雪花号：让断言能对着具体的 id 说话（真实实现与时间相关，测不了相等）。 */
public class SequentialIds implements IdGenerator {

    private long next = 600_000_000_000_000_000L;

    @Override
    public long nextId() {
        return ++next;
    }

    @Override
    public int nodeId() {
        return 1;
    }
}
