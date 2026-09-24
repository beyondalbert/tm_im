package com.tm.im.domain.repository;

import com.tm.im.domain.entity.Friendship;
import com.tm.im.domain.enums.FriendshipStatus;

import java.util.List;
import java.util.Optional;

/**
 * 好友关系仓储。
 *
 * <p>存储约定（见 DDL）：关系以<b>无序对</b>存储，即始终 {@code actor_a < actor_b}，
 * 方向信息放在 {@code initiator} 列。好处：判断「是否是好友」只需一次点查，
 * 且天然不会出现 A→B 与 B→A 两条记录不一致的情况。
 *
 * <p>因此本接口的所有方法都必须做<b>参数归一化</b>，不能让调用方自己保证顺序——
 * 一旦有人传反，查询会静默查不到（返回「不是好友」），
 * 表现为「明明是好友却发不出消息」这种极难定位的问题。
 */
public interface FriendshipRepository {

    /** 按无序对查询。内部会做 min/max 归一化。 */
    Optional<Friendship> find(long actorX, long actorY);

    /** 判断是否为「已是好友」（状态必须为 ACCEPTED，PENDING 不算）。 */
    boolean areFriends(long actorX, long actorY);

    /** 写入或更新关系（同一个无序对只允许一条记录）。 */
    void save(Friendship friendship);

    /** 更新关系状态。 */
    void updateStatus(long actorX, long actorY, FriendshipStatus status);

    /**
     * 列出某人的好友 ID。广场排序与消息扇出都要用。
     *
     * <p>因为关系存的是无序对，好友可能出现在 {@code actor_a} 也可能在 {@code actor_b}，
     * 实现必须两个方向都查——这是本表设计带来的唯一代价。
     */
    List<Long> listFriendIds(long actorId, int limit);
}
