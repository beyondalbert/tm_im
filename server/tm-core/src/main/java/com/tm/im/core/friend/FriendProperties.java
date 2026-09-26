package com.tm.im.core.friend;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 好友关系的策略配置 —— {@code tm.friend} 段。
 *
 * <p>三条都是「策略」而不是「机制」：机制是「非好友不能发消息」（DESIGN §11.6，
 * 硬规则、不可配置），策略是「请求多久过期」「一天能发几次」「被拉黑后还能不能再试」。
 * 把硬规则也做成配置项，等于给了一个「把产品红线关掉」的开关。
 *
 * <p><b>刻意的缺项</b>：模板里曾有一行 {@code cache-ttl-seconds}（好友集缓存 TTL），
 * 而代码里没有缓存——好友关系每次都回源数据库。留着那一行比删掉它更糟：
 * 运维会以为有一个缓存需要调，而它不存在；排查「为什么加了好友还能发消息」
 * 时会先从缓存一致性入手。缓存本身是 DESIGN §11.6 的设计，
 * 但它要连带解决**跨节点失效**（一个节点的内存缓存删不掉另一个节点的），
 * 属于另一件事（见 DESIGN §14.1 的待办）。
 */
@ConfigurationProperties(prefix = "tm.friend")
public class FriendProperties {

    /**
     * 好友请求的失效天数（默认 7）。
     *
     * <p>过期的 PENDING 行**按不存在处理**，而不是先跑一个定时任务去删它：
     * 判断过期只需要比一次时间（请求上就带着 {@code expires_at}），
     * 而一个清理任务会带来「任务没跑起来时行为就变了」这种新的失败模式。
     * 行本身会在下一次同一个人再发请求时被覆盖。
     */
    private int requestExpireDays = 7;

    /**
     * 是否允许<b>被拉黑的人</b>再次发起请求（默认 false）。
     *
     * <p>默认关闭是有意的：拉黑是「我不想再收到你的任何东西」，
     * 而允许再次请求会让它退化成「我暂时不想理你」——被拉黑的一方还能继续敲。
     * 打开它的场景是「屏蔽骚扰但保留申诉通道」，那属于产品决策。
     */
    private boolean allowRequestAfterBlock = false;

    /**
     * 人类每天能发出的好友请求数（默认 50，Agent 见 {@code tm.agent.friend-request-daily-quota}）。
     *
     * <p>两个配额分开放，是因为它们的依据不同：人类的 50 是「防手滑与误操作」的量级，
     * Agent 的 100 是「防批量扫描式加好友」的量级——而这两种主体在文档里
     * 本来就有不同的配额表（07-errors-limits.md §3.1）。
     */
    private int requestDailyQuota = 50;

    public int getRequestExpireDays() {
        return requestExpireDays;
    }

    public void setRequestExpireDays(int requestExpireDays) {
        this.requestExpireDays = requestExpireDays;
    }

    public boolean isAllowRequestAfterBlock() {
        return allowRequestAfterBlock;
    }

    public void setAllowRequestAfterBlock(boolean allowRequestAfterBlock) {
        this.allowRequestAfterBlock = allowRequestAfterBlock;
    }

    public int getRequestDailyQuota() {
        return requestDailyQuota;
    }

    public void setRequestDailyQuota(int requestDailyQuota) {
        this.requestDailyQuota = requestDailyQuota;
    }
}
