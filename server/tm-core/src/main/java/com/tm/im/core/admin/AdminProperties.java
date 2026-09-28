package com.tm.im.core.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 管理后台配置 —— {@code tm.admin} 段（M9）。
 *
 * <p><b>这一段里没有「后台密码」这类东西</b>：第一个管理员的账号与口令由
 * {@code tm.admin.bootstrap.*} 在<b>账号表为空时</b>使用一次，之后配置就失去作用
 * （见 {@code AdminService#bootstrap}）。这条约束比「加密配置里的口令」实用得多：
 * 一个只在首次启动生效的口令，即使泄漏了也没有可用的时间窗口。
 */
@ConfigurationProperties(prefix = "tm.admin")
public class AdminProperties {

    /**
     * 后台会话有效期。
     *
     * <p>比人类用户的 JWT（2 小时）短，尽管后台会话是「可即时吊销」的：
     * 吊销要有人去点，而一个能封任何人号的管理员会话如果没人管，
     * 它的剩余寿命就是攻击者的可用窗口。8 小时略长于一个工作日——
     * 让运维一天登录一次，而不是每次操作都重新认证。
     */
    private Duration sessionTtl = Duration.ofHours(8);

    /**
     * 连续登录失败多少次后锁定账号。
     *
     * <p>后台账号是<b>高价值、低数量</b>的目标：用户名往往是 {@code admin} 这种
     * 可猜的字符串，而口令一旦被撞开，攻击者拿到的是全站封号权。
     * 因此这里的策略比用户端严格，且计数落在库里（{@code admin_user.failed_attempts}）
     * 而不是单个节点的内存里——多实例部署时，内存计数等于「每个实例各给 5 次机会」。
     */
    private int maxLoginFailures = 5;

    /** 触发锁定后的锁定时长。到点自动解锁，不需要人工干预。 */
    private Duration lockoutDuration = Duration.ofMinutes(15);

    /**
     * 后台口令长度下限。
     *
     * <p>比用户端的 8 位更长是有意的：后台账号没有「验证码 / 二次验证」这道补充防线，
     * 而它一旦失守，损失面是整个平台而不是一个账号。
     */
    private int minPasswordLength = 12;

    /** 后备列表默认页大小。 */
    private int pageSize = 20;

    /** 后备列表最大页大小（客户端传更大的值会被夹到这个数，而不是报错）。 */
    private int maxPageSize = 100;

    /** 第一个管理员的用户名。仅当 {@code admin_user} 表为空时使用。 */
    private String bootstrapUsername = "";

    /** 第一个管理员的口令。仅当 {@code admin_user} 表为空时使用。 */
    private String bootstrapPassword = "";

    /** 第一个管理员的显示名（留空则用用户名）。 */
    private String bootstrapDisplayName = "";

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }

    public int getMaxLoginFailures() {
        return maxLoginFailures;
    }

    public void setMaxLoginFailures(int maxLoginFailures) {
        this.maxLoginFailures = maxLoginFailures;
    }

    public Duration getLockoutDuration() {
        return lockoutDuration;
    }

    public void setLockoutDuration(Duration lockoutDuration) {
        this.lockoutDuration = lockoutDuration;
    }

    public int getMinPasswordLength() {
        return minPasswordLength;
    }

    public void setMinPasswordLength(int minPasswordLength) {
        this.minPasswordLength = minPasswordLength;
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

    public String getBootstrapUsername() {
        return bootstrapUsername;
    }

    public void setBootstrapUsername(String bootstrapUsername) {
        this.bootstrapUsername = bootstrapUsername;
    }

    public String getBootstrapPassword() {
        return bootstrapPassword;
    }

    public void setBootstrapPassword(String bootstrapPassword) {
        this.bootstrapPassword = bootstrapPassword;
    }

    public String getBootstrapDisplayName() {
        return bootstrapDisplayName;
    }

    public void setBootstrapDisplayName(String bootstrapDisplayName) {
        this.bootstrapDisplayName = bootstrapDisplayName;
    }
}
