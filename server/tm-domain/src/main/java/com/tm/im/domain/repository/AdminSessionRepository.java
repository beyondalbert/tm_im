package com.tm.im.domain.repository;

import com.tm.im.domain.entity.AdminSession;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 后台会话仓储 —— 一张「可即时吊销」的表。
 *
 * <p><b>为什么后台会话在库里，而人类账号用 JWT</b>：
 * <ul>
 *   <li>JWT 的吊销需要一张黑名单表，而黑名单表就是本表去掉「签发」那一步；</li>
 *   <li>后台账号只有几个人、QPS 以「天」计，一次按哈希的点查完全不是成本；</li>
 *   <li>停用一个管理员、或他自己点了登出，<b>必须立刻生效</b>——
 *     JWT 只能等它过期，而管理员手里那把钥匙的权限是「能封任何人的号」。</li>
 * </ul>
 * 人类账号（几百万级、每个请求都要鉴权）走 JWT 的理由恰好是这里的反面。
 *
 * <p>落库的只有 {@code SHA-256(明文)}：读库的人不应该拿到能直接用的凭证，
 * 与 {@code api_key} 同一条约定。
 */
public interface AdminSessionRepository {

    void insert(AdminSession session);

    /** 按 {@code token_hash} 点查（{@code uk_token_hash}）。调用方传的是哈希，不是明文。 */
    Optional<AdminSession> findByTokenHash(String tokenHash);

    /** 记一下「这个会话最近被用过」。失败不影响鉴权结果，所以实现可以吞掉异常。 */
    void touch(long sessionId, LocalDateTime at);

    /** 登出。返回是否真的删掉了一行——<b>调用方不据此报错</b>（登出是幂等的）。 */
    boolean deleteByTokenHash(String tokenHash);

    /**
     * 踢掉某个管理员的全部会话。
     *
     * <p>停用一个账号时必须与状态写入在<b>同一个事务</b>里：分开写的表现是
     * 「停用成功但旧 token 还能用一段时间」，而那正是停用最需要立刻生效的时刻。
     */
    int deleteByAdminId(long adminId);

    /** 清理过期会话（后台定时任务或运维手动跑）。 */
    int deleteExpired(LocalDateTime before);
}
