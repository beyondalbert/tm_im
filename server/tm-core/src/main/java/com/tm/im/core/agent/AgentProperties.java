package com.tm.im.core.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 风控与投递配置 —— {@code tm.agent} 段。
 *
 * <p><b>注意这里没有「非好友不能发消息」这条规则的开关</b>：它是产品红线
 * （DESIGN §11.6），写在 {@code MessageSendPolicy} 里、不可配置。
 * 本类只放第二道防线（速率、配额）与 Webhook 投递参数。
 *
 * <p>默认值的口径与 07-errors-limits.md §3.1 的配额表一致；
 * 人类与 Agent 的限流<b>维度完全相同</b>，只有个别配额按使用场景不同
 * （如好友请求：人类 50、Agent 100）。
 */
@ConfigurationProperties(prefix = "tm.agent")
public class AgentProperties {

    /**
     * 新建 Agent 时的默认「每分钟消息数」。
     *
     * <p>写入 {@code agent_profile.rate_limit}（那是**每个 Agent 自己的**配额，
     * 创建时可以从这里覆盖），而不是每次发送时读本值——否则调整一次全局默认值
     * 会同时改变所有已存在 Agent 的行为，而那不是一个能安全回退的操作。
     */
    private int defaultRateLimitPerMinute = 60;

    /** 新建 Agent 时的默认日配额（条/天）。 */
    private int defaultDailyQuota = 5000;

    /**
     * Agent 每天能发出的好友请求数（默认 100，人类见 {@code tm.friend.request-daily-quota}）。
     *
     * <p>这是「防 Agent 批量加好友骚扰」的主要闸门。产品上真正拦得住骚扰的是
     * 「好友请求必须被人工同意」这条链路本身，配额只是让它不能把请求面板刷满。
     */
    private int friendRequestDailyQuota = 100;

    /** Webhook 投递的超时（毫秒）。超时即视为失败并按 {@link #webhookMaxRetry} 重试。 */
    private int webhookTimeoutMs = 5000;

    /** Webhook 失败后的最大重试次数（不含首次投递）。 */
    private int webhookMaxRetry = 3;

    /**
     * 签名头名称（默认 {@code X-TM-Signature}）。
     *
     * <p>可配置是为了让 Agent 方能在中间件已经占用该头名时换一个；
     * 但换它意味着**平台与 Agent 两边必须同时改**，所以它只适合成对部署的场景。
     */
    private String signatureHeader = "X-TM-Signature";

    public int getDefaultRateLimitPerMinute() {
        return defaultRateLimitPerMinute;
    }

    public void setDefaultRateLimitPerMinute(int defaultRateLimitPerMinute) {
        this.defaultRateLimitPerMinute = defaultRateLimitPerMinute;
    }

    public int getDefaultDailyQuota() {
        return defaultDailyQuota;
    }

    public void setDefaultDailyQuota(int defaultDailyQuota) {
        this.defaultDailyQuota = defaultDailyQuota;
    }

    public int getFriendRequestDailyQuota() {
        return friendRequestDailyQuota;
    }

    public void setFriendRequestDailyQuota(int friendRequestDailyQuota) {
        this.friendRequestDailyQuota = friendRequestDailyQuota;
    }

    public int getWebhookTimeoutMs() {
        return webhookTimeoutMs;
    }

    public void setWebhookTimeoutMs(int webhookTimeoutMs) {
        this.webhookTimeoutMs = webhookTimeoutMs;
    }

    public int getWebhookMaxRetry() {
        return webhookMaxRetry;
    }

    public void setWebhookMaxRetry(int webhookMaxRetry) {
        this.webhookMaxRetry = webhookMaxRetry;
    }

    public String getSignatureHeader() {
        return signatureHeader;
    }

    public void setSignatureHeader(String signatureHeader) {
        this.signatureHeader = signatureHeader;
    }
}
