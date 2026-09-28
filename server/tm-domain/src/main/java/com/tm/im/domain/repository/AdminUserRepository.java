package com.tm.im.domain.repository;

import com.tm.im.domain.entity.AdminUser;
import com.tm.im.domain.enums.AdminStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 后台账号仓储。
 *
 * <p><b>没有 {@code delete}</b>：删掉一个后台账号会让审计表里的 {@code admin_id}
 * 变成指向虚无的数字（审计行本身保留了用户名快照，但那是兜底而不是设计）。
 * 停用（{@code status=DISABLED}）已经能表达「这个人不再能登录」，
 * 而它同时保留了「他以前干过什么」的可读性。
 *
 * <p><b>列表分页按 {@code id} 倒序</b>：{@code id} 是 Snowflake，
 * 单调递增且与创建时间同序，所以「按 id 倒序」就是「按创建时间倒序」，
 * 而游标只需要带一个 id（不必带时间戳再去处理同毫秒并列）。
 */
public interface AdminUserRepository {

    Optional<AdminUser> findById(long adminId);

    Optional<AdminUser> findByUsername(String username);

    /** 全表计数（后台账号只有几个人，这个点查成本可以忽略）。 */
    long count();

    void insert(AdminUser admin);

    /**
     * 更新可变的账号字段：{@code display_name} / {@code role} / {@code status}。
     *
     * <p>刻意不是「更新全部字段」：{@code username} 与 {@code created_at} 是不可变的
     * （改用户名等于换个人，而审计行里的快照会与账号对不上）。
     * 口令与登录状态各有专门的方法，理由见下。
     */
    void update(AdminUser admin);

    /**
     * 覆盖口令哈希。
     *
     * <p>单独一个方法而不是并入 {@link #update}：并入之后，
     * 「只是想改个显示名」的调用方必须先把 {@code passwordHash} 读出来再写回去，
     * 而任何一次读漏都会把一个账号的密码变成「空哈希」——那等于该账号无法登录，
     * 或者更糟：若校验实现把空哈希当成匹配。写入路径越窄，这类事故越不可能发生。
     */
    void updatePasswordHash(long adminId, String passwordHash);

    /**
     * 一次性更新登录状态：失败计数、锁定到什么时候、上次登录时间。
     *
     * <p>三者在同一条 UPDATE 里，因为它们是同一次登录尝试的三个结果：
     * 分开写会留下「计数加了但锁定时间没写」的中间态，而在防爆破这条路径上，
     * 中间态的代价是攻击者只要让请求失败在两次写之间就能永远不被锁定。
     *
     * @param lockedUntil 可为 null，表示「不锁定」
     */
    void updateLoginState(long adminId, int failedAttempts, LocalDateTime lockedUntil,
                          LocalDateTime lastLoginAt);

    /** 按 id 倒序分页；{@code beforeId} 为 null 表示第一页。 */
    List<AdminUser> page(Long beforeId, int limit);

    /** 停用 / 启用时的状态写入（与 {@link #update} 分开，避免误改角色）。 */
    void updateStatus(long adminId, AdminStatus status);
}
