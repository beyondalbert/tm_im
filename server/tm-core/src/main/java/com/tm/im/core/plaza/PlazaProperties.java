package com.tm.im.core.plaza;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 广场（信息流）的策略配置 —— {@code tm.feed} 段。
 *
 * <p>与 {@code FriendProperties} 同一取舍：这里只放<b>策略</b>（配额、页大小、
 * 扩散的规模阈值），不放<b>机制</b>。机制是「非好友看不到仅好友可见的动态」
 * （DESIGN §11.3 的可见性规则）与「只有作者能删自己的动态」——把
 * 「谁能看到什么」做成配置项，等于提供了一个把产品红线关掉的开关。
 *
 * <p>配置项的名字与部署模板 {@code deploy/conf/application-external.yml.example}
 * 的 {@code tm.feed} 段逐条对应；{@code verify_config_template} 会检查两边是否一致。
 */
@ConfigurationProperties(prefix = "tm.feed")
public class PlazaProperties {

    /**
     * 好友加权的分值（默认 524288 = {@code 0x80000}，DESIGN §11.3 的中位）。
     *
     * <p>它必须落在 {@code [0, 0xFFFFF]} 内：再大就会溢进高位的时间位，
     * 那会让「时间倒序」这个主要排序规则被好友加权覆盖——
     * 表现是信息流顶部永远是一年前的某条好友动态。越界由
     * {@code FeedScores} 直接抛异常（构造期就炸，而不是等到线上才发帖）。
     */
    private int friendBoost = 524288;

    /**
     * 大 V 阈值：好友数超过它的作者<b>不做写扩散</b>（默认 10000）。
     *
     * <p>写扩散的代价是「一次发帖 = 好友数行 INSERT」。一万行已经是
     * 一次不该在请求链路上等着的事，十万行则会把它变成一个必须排队几分钟的任务。
     *
     * <p><b>当前实现的缺口</b>：DESIGN §11.3 说「大 V 跳过，改读扩散」，
     * 而读扩散（读时按作者拉取）尚未实现——超阈值的作者其动态不会出现在
     * 好友的信息流里（会记一条 WARN，公开动态仍出现在所有人的公开流段）。
     * 这是这一版明确记录的欠账，不是静默行为。
     */
    private int celebrityThreshold = 10000;

    /** 信息流默认页大小（客户端不传 limit 时，默认 20）。 */
    private int pageSize = 20;

    /** 单次请求的最大条数（客户端传再多也只给这么多）。 */
    private int maxPageSize = 200;

    /** 人类每天能发的动态数（默认 20，Agent 见 {@link #getAgentPostDailyQuota()}）。 */
    private int postDailyQuota = 20;

    /** Agent 每天能发的动态数（默认 50）。依据与好友请求同一套：防批量刷屏。 */
    private int agentPostDailyQuota = 50;

    /** 动态正文长度上限（默认 5000 字符，即 40006 的判据）。 */
    private int maxTextLength = 5000;

    /** 单条评论长度上限（默认 1000 字符）。与正文分开：评论区比正文短得多。 */
    private int maxCommentLength = 1000;

    /** 一条动态最多几张图（默认 9）。 */
    private int maxImages = 9;

    /** 写扩散线程数（默认 4）。 */
    private int fanoutThreads = 4;

    /**
     * 写扩散任务的排队上限（默认 2000）。
     *
     * <p>满了就<b>丢弃</b>（记 WARN）而不是阻塞调用线程：发帖接口的响应时间
     * 不该由「扩散队列有多长」决定。丢掉的后果是那条动态没进某些好友的收件箱，
     * 而它仍然出现在公开流里、也出现在作者的个人页——比一次 5 秒的发帖请求轻。
     */
    private int fanoutQueueCapacity = 2000;

    /**
     * 计算公开流时最多排除多少个好友（默认 2000）。
     *
     * <p>公开流的 SQL 里有一句 {@code author_id NOT IN (...)}，列表就是调用者的好友集。
     * 它有上限不是为了性能，而是因为「排不掉的好友」会导致公开流里出现重复动态——
     * 超限时记 WARN 并继续（少排除的人偏多），而不是把公开流整段关掉。
     */
    private int friendSetCap = 2000;

    public int getFriendBoost() {
        return friendBoost;
    }

    public void setFriendBoost(int friendBoost) {
        this.friendBoost = friendBoost;
    }

    public int getCelebrityThreshold() {
        return celebrityThreshold;
    }

    public void setCelebrityThreshold(int celebrityThreshold) {
        this.celebrityThreshold = celebrityThreshold;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    public int getPostDailyQuota() {
        return postDailyQuota;
    }

    public void setPostDailyQuota(int postDailyQuota) {
        this.postDailyQuota = postDailyQuota;
    }

    public int getAgentPostDailyQuota() {
        return agentPostDailyQuota;
    }

    public void setAgentPostDailyQuota(int agentPostDailyQuota) {
        this.agentPostDailyQuota = agentPostDailyQuota;
    }

    public int getMaxTextLength() {
        return maxTextLength;
    }

    public void setMaxTextLength(int maxTextLength) {
        this.maxTextLength = maxTextLength;
    }

    public int getMaxCommentLength() {
        return maxCommentLength;
    }

    public void setMaxCommentLength(int maxCommentLength) {
        this.maxCommentLength = maxCommentLength;
    }

    public int getMaxImages() {
        return maxImages;
    }

    public void setMaxImages(int maxImages) {
        this.maxImages = maxImages;
    }

    public int getFanoutThreads() {
        return fanoutThreads;
    }

    public void setFanoutThreads(int fanoutThreads) {
        this.fanoutThreads = fanoutThreads;
    }

    public int getFanoutQueueCapacity() {
        return fanoutQueueCapacity;
    }

    public void setFanoutQueueCapacity(int fanoutQueueCapacity) {
        this.fanoutQueueCapacity = fanoutQueueCapacity;
    }

    public int getFriendSetCap() {
        return friendSetCap;
    }

    public void setFriendSetCap(int friendSetCap) {
        this.friendSetCap = friendSetCap;
    }
}
