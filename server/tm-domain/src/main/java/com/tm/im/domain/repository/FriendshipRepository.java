package com.tm.im.domain.repository;

import com.tm.im.domain.entity.Friendship;
import com.tm.im.domain.enums.FriendshipStatus;

import java.time.LocalDateTime;
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
 *
 * <p><b>本表同时承载「请求」的生命周期</b>（DESIGN §11.4）：一行从 PENDING 开始，
 * 被接受后变成 ACCEPTED，或被删除（拒绝/超时）。所以「好友请求」没有独立的表，
 * 也就不会出现「请求被接受了、但关系表说不是好友」这种两表不一致。
 * {@code request_id} 是这一行的对外 id（客户端 accept/reject 按它定位）。
 *
 * <p><b>无序对带来的一个代价</b>：按「对方 id」排序分页做不到（对方可能在
 * {@code actor_a} 也可能在 {@code actor_b}，同一次查询里两种都可能）。因此列表类方法
 * 一律返回<b>有界的一批行</b>，由调用方合并两个方向后再排序、再分页
 * （{@code FriendService} 的做法与理由见那里的注释）。
 */
public interface FriendshipRepository {

    /**
     * 列表类方法的游标：{@code (updated_at, request_id)}。
     *
     * <p><b>为什么第二个字段用 {@code request_id} 而不是「对方的 actor_id」</b>：
     * 关系存的是无序对，对方可能在 {@code actor_a} 也可能在 {@code actor_b}，
     * 所以「按对方 id 定序」这条式子在一条 SQL 里对两个方向是不一样的
     * （{@code actor_b < ?} 与 {@code actor_a < ?} 得写成两份）。
     * 而 {@code request_id} 是每一行自己的列，两个方向用的是同一个谓词——
     * 游标的「并列时的定序键」必须满足这个条件，否则分页在「同一毫秒里接受了多个好友」
     * 时会重复或漏项。它同时是唯一索引，所以用它定序也不会有并列。
     */
    record Cursor(LocalDateTime updatedAt, long requestId) {
    }

    /** 按无序对查询。内部会做 min/max 归一化。 */
    Optional<Friendship> find(long actorX, long actorY);

    /** 按请求 id 查询（accept/reject/详情按它定位）。 */
    Optional<Friendship> findByRequestId(long requestId);

    /** 判断是否为「已是好友」（状态必须为 ACCEPTED，PENDING 不算）。 */
    boolean areFriends(long actorX, long actorY);

    /**
     * 写入或更新关系（同一个无序对只允许一条记录）。
     *
     * <p>入参的 {@code requestId} 必须由调用方给出（雪花号）：它是对外契约的一部分，
     * 「这次请求的 id 是什么」只有业务层在生成它的时候知道。
     * 新增行时 {@code createdAt} 缺失会退化为「此刻」（并打 WARN，理由见
     * {@code ConversationRepositoryImpl.insert}：JVM 默认时区与 {@code tm.time.zone}
     * 可能不一致，生产路径必须显式给值）。
     */
    void save(Friendship friendship);

    /** 更新关系状态。 */
    void updateStatus(long actorX, long actorY, FriendshipStatus status);

    /**
     * 删除一行关系。用于：拒绝请求、删除好友、解除拉黑。
     *
     * @return 是否真的删掉了（false = 本来就没有这一行）
     */
    boolean delete(long actorX, long actorY);

    /**
     * 列出某人的好友 ID。广场排序与消息扇出都要用。
     *
     * <p>因为关系存的是无序对，好友可能出现在 {@code actor_a} 也可能在 {@code actor_b}，
     * 实现必须两个方向都查——这是本表设计带来的唯一代价。
     */
    List<Long> listFriendIds(long actorId, int limit);

    /**
     * 好友列表的一页：{@code status=ACCEPTED} 且 {@code updated_at} 早于游标（游标为空则最新的一页）。
     *
     * <p>它不区分方向：调用方拿到的是两个方向合并后的一批行（已按 {@code updated_at DESC,
     * request_id DESC} 排好），「对方是谁」由调用方根据自己是不是 {@code actor_a} 判断。
     * 这样列表分页只依赖一个谓词，不需要为两个方向各定义一套游标。
     */
    List<Friendship> pageFriends(long actorId, Cursor cursor, int limit);

    /**
     * 请求列表的一页，两个方向合并。
     *
     * @param status        只要这个状态；{@code null} 表示不限（{@code status=all}）
     * @param initiatedByMe 只要我发起的（true）/ 只要别人发给我的（false）；{@code null} 不限
     */
    List<Friendship> pageRequests(long actorId, FriendshipStatus status, Boolean initiatedByMe,
                                  Cursor cursor, int limit);

    /**
     * 统计「我在某个时刻之后发起的」行数 —— 好友请求日配额用。
     *
     * <p>为什么查库而不是用 Redis 计数器（DESIGN §10.4 里有 {@code tm:rl:*}）：
     * 计数器是「内存里的一行数字」，重启即丢，而配额被绕过一次就够刷屏了；
     * 而这张表上的 {@code idx_initiator_status} 让这次查询是一次普通的索引范围扫描
     * （索引前缀正好是 initiator），一天的量级很小。限流器该管的是高频路径（发消息），
     * 不是每天几十次的加好友。
     */
    int countInitiatedSince(long actorId, LocalDateTime since);
}
