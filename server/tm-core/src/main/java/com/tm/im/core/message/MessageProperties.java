package com.tm.im.core.message;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 消息与扇出配置（{@code tm.message.*}）。
 *
 * <p>几个值的共同点：它们都是<b>产品语义</b>而不是性能参数——改一下，
 * 客户端看到的行为就变了。所以它们必须可配置且能与
 * {@code deploy/conf/application-external.yml.example} 对照
 * （由 {@code tools/verify_config_template.py} 机器校验），
 * 而不是散在代码里当常量。
 */
@ConfigurationProperties(prefix = "tm.message")
public class MessageProperties {

    /**
     * 写扩散上限（DESIGN §10.2）。
     *
     * <p>≤ 该人数的会话逐成员推送；更大则只落库、由客户端按 {@code last_seq} 拉。
     * 不分级的话，10 万人群发一条消息会触发 10 万次写，直接打趴系统。
     */
    private int writeFanoutThreshold = 500;

    /**
     * 离线消息保留天数（04-realtime.md §6.4）。
     *
     * <p>断点续传只能补保留期内的消息；更早的会以 {@code SyncResponse.truncated=true}
     * 告知客户端改走 REST 拉历史。
     */
    private int offlineRetentionDays = 30;

    /**
     * 单次拉取上限（REST 与 SYNC 共用）。
     *
     * <p>它同时是「一次请求最多打多少行」的配额：客户端传 {@code limit=100000}
     * 不能真的让它拉 10 万行，否则一个恶意请求就能拖垮一个分片。
     */
    private int maxPullSize = 200;

    /**
     * 单条文字消息的最大长度（07-errors-limits.md §2.1：文字 ≤ 5000 字符）。
     *
     * <p>超限回 40006 而不是截断：静默截断会让用户以为发出去的是完整内容，
     * 而对方看到的少了一截，且没有任何一方能发现。
     */
    private int maxTextLength = 5000;

    public int getWriteFanoutThreshold() {
        return writeFanoutThreshold;
    }

    public void setWriteFanoutThreshold(int writeFanoutThreshold) {
        this.writeFanoutThreshold = writeFanoutThreshold;
    }

    public int getOfflineRetentionDays() {
        return offlineRetentionDays;
    }

    public void setOfflineRetentionDays(int offlineRetentionDays) {
        this.offlineRetentionDays = offlineRetentionDays;
    }

    public int getMaxPullSize() {
        return maxPullSize;
    }

    public void setMaxPullSize(int maxPullSize) {
        this.maxPullSize = maxPullSize;
    }

    public int getMaxTextLength() {
        return maxTextLength;
    }

    public void setMaxTextLength(int maxTextLength) {
        this.maxTextLength = maxTextLength;
    }

    /**
     * 把客户端传的 limit 收敛到 {@code [1, maxPullSize]}。
     *
     * <p>{@code limit <= 0} 视为「用服务端默认值」而不是「拉 0 条」：
     * 协议里 0 的语义就是「用默认」（见 transport.proto 的 {@code SyncRequest.limit}）。
     */
    public int clampPullSize(int limit) {
        if (limit <= 0) {
            return maxPullSize;
        }
        return Math.min(limit, maxPullSize);
    }
}
