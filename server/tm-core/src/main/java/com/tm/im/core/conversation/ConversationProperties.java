package com.tm.im.core.conversation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话与消息查询的配置（{@code tm.conversation.*}）。
 *
 * <p><b>为什么页大小要可配</b>：它是「产品语义」而不是性能参数——改一下，
 * 客户端一次能看到多少条就变了。所以它必须出现在
 * {@code deploy/conf/application-external.yml.example} 里（由
 * {@code tools/verify_config_template.py} 机器校验），而不是散在代码里当常量。
 *
 * <p><b>{@code tm.message.max-pull-size} 管的是长连接</b>（{@code CMD_SYNC} 单游标上限），
 * 这里的两个值管的是 REST。刻意没有合并成一个：两条链路的瓶颈不同——
 * SYNC 是「一帧里多个游标 × 每游标 limit」，REST 是「一次请求一个会话」——
 * 合起来之后调整一边必然动到另一边。
 */
@ConfigurationProperties(prefix = "tm.conversation")
public class ConversationProperties {

    /**
     * 建群时一个群最多多少人（含群主）。超出回 40906。
     *
     * <p>默认 500 与 {@code tm.message.write-fanout-threshold} 同量级不是巧合：
     * 超过那个人数，扇出就从「逐成员推送」退化为「客户端按 last_seq 自己拉」，
     * 而「建群时允许多少人」应当先于「推送怎么降级」决定——反过来定会造出
     * 一个「建得出但推不动」的群。
     */
    private int maxGroupMembers = 500;

    /**
     * REST 列表/历史的默认页大小（客户端不传 {@code limit} 时）。
     *
     * <p>取 50 而不是取硬上限：默认值决定「一次点击打多少行」，而 200 条消息
     * 在首屏没有任何用处。客户端要更多就显式传 {@code limit}。
     */
    private int defaultPageSize = 50;

    /** 单次请求返回的最大条数。客户端传再多也只给这么多（否则一个请求就能拖垮一个分片）。 */
    private int maxPageSize = 200;

    public int getMaxGroupMembers() {
        return maxGroupMembers;
    }

    public void setMaxGroupMembers(int maxGroupMembers) {
        this.maxGroupMembers = maxGroupMembers;
    }

    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    public void setDefaultPageSize(int defaultPageSize) {
        this.defaultPageSize = defaultPageSize;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    /**
     * 把客户端传的 limit 收敛到 {@code [defaultPageSize, maxPageSize]}。
     *
     * <p>{@code limit <= 0} 视为「用服务端默认值」而不是「要 0 条」——与
     * {@code MessageProperties.clampPullSize} 同一口径（proto 里 0 的语义就是「用默认」）。
     */
    public int clampPageSize(int limit) {
        if (limit <= 0) {
            return defaultPageSize;
        }
        return Math.min(limit, maxPageSize);
    }
}
