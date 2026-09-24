package com.tm.im.domain.repository;

import com.tm.im.domain.entity.Conversation;
import com.tm.im.domain.entity.ConversationMember;

import java.util.List;
import java.util.Optional;

/**
 * 会话仓储。单聊与群聊共用同一套结构（{@code conv_type} 区分），
 * 这样消息表不需要为两种会话分别设计。
 */
public interface ConversationRepository {

    Optional<Conversation> findById(long convId);

    /**
     * 按去重键查找单聊会话。
     *
     * <p>去重键格式为 {@code minActorId_maxActorId}（见 {@code PairKeys}）。
     * 有了它，A 发起与 B 发起会命中同一个会话，
     * 不需要依赖「先查再插」的应用层竞态控制——数据库的唯一索引会兜底。
     */
    Optional<Conversation> findDirectByPairKey(String pairKey);

    Conversation insert(Conversation conversation);

    /** 新增成员。已存在时返回 false（幂等，可安全重试）。 */
    boolean addMember(long convId, long actorId, com.tm.im.domain.enums.MemberRole role);

    /** 幂等：重复调用与调用一次等价。 */
    void updateLastReadSeq(long convId, long actorId, long lastReadSeq);

    Optional<ConversationMember> findMember(long convId, long actorId);

    boolean isMember(long convId, long actorId);

    /** 拉某人的会话列表（按最近活跃排序由实现决定）。 */
    List<Conversation> listByActor(long actorId, int limit);

    List<Long> listMemberIds(long convId, int limit);

    /**
     * 分配会话内下一个序号：同会话内<b>不重复、严格递增</b>。
     *
     * <p><b>唯一序号源</b>。消息表的物理主键是 {@code (conv_id, seq)}，
     * 因此序号一旦重复，后果不是「顺序乱了」而是<b>那条消息插不进去</b>——
     * 表现为「发出去没反应」，而客户端与网络都正常。所以「不重复」比「连续」重要得多：
     * 这里允许出现空洞（重试、对账都会消耗号），但不允许回退。
     *
     * <p>实现约定：优先用 Redis {@code INCR tm:seq:{convId}}（一趟内存往返、
     * 天然原子、且分配顺序即时间顺序）；Redis 不可用时退化到数据库计数器
     * {@link #nextSeqFromDb}。两条路径都必须能自 {@code floor}（库内已有的最大 seq）
     * 之上继续，否则 Redis 数据被清空后序号会从头开始并撞主键。
     */
    long nextSeq(long convId);

    /**
     * 把序号源的基线抬到不低于 {@code floor} —— <b>序号源自愈</b>。
     *
     * <p><b>后置条件</b>：本方法返回之后，下一次 {@link #nextSeq} 得到的值
     * 为 {@code max(自愈前的当前值, floor) + 1}。也就是说自愈本身<b>不消耗号</b>——
     * 它的责任是把基线抬到位，而不是替调用方占一个坑。
     *
     * <p>为什么需要它：Redis 被清空（flush / 无持久化重启 / 故障切换）之后，
     * {@code tm:seq:{convId}} 会从 1 重新开始，而库里已经有 seq=1..N 的消息，
     * 于是接下来 N 条消息全部撞主键。检测点是唯一的：插库时撞了
     * {@code PRIMARY (conv_id, seq)}。此时调用方用「库内实际最大 seq」调用本方法，
     * 序号源即被抬到 floor 之上，重试成功且后续不再撞。
     *
     * <p>实现必须是<b>条件式</b>的（仅当当前值 < floor 时才抬升），
     * 否则并发调用会互相把序号往回拉，比不修还糟。
     *
     * @param floor 库内该会话已有的最大 seq；0 或负数表示无历史消息，实现应忽略
     */
    void raiseSeqFloor(long convId, long floor);

    /**
     * 从数据库推进序号 —— <b>仅作为 Redis 不可用时的兜底</b>。
     *
     * <p>正常路径是 Redis INCR（{@link #nextSeq}）：一趟内存往返。
     * 这里的实现依赖 {@code UPDATE conversation SET seq_counter = ...} 拿到的行锁，
     * 并发的调用会在该行上串行化，因此不会取到重复序号。
     * 代价是每次分配都要写一次磁盘页，吞吐远低于 Redis——所以它只是兜底，
     * 一旦被高频调用就说明 Redis 出问题了，应当告警而不是默默扛着。
     *
     * @param floor 库内该会话已有的最大 seq。计数器可能长期没被写过
     *              （正常路径不更新它），若直接自增就会发出早已用过的序号，
     *              因此实现必须从 {@code max(seq_counter, floor)} 之上继续。
     */
    long nextSeqFromDb(long convId, long floor);
}
