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

    /**
     * 原子建群：会话行与全部成员行在<b>同一个事务</b>里提交。
     *
     * <p>为什么不复用 {@link #insert} + {@link #addMember} 逐个写：那是 N+1 个事务，
     * 中途崩溃会留下一个「成员不全的群」——而客户端重试会再建一个新的
     * （§4.2 的建群没有幂等键），于是用户看到两个残缺的群，且没有任何一处报错。
     * 成员数上限（几百）由调用方在进入这里之前判掉，所以本方法不做数量校验：
     * 一个「写一半失败」的语义比一个「上限校验」难处理得多。
     *
     * @param conversation 会话行，{@code id} 必须已由调用方生成（Snowflake，非自增）
     * @param members      成员行，{@code convId} 由本方法回填；{@code joinedAt} 必须由调用方给出
     */
    void createGroup(Conversation conversation, List<ConversationMember> members);

    /**
     * 新增成员。已存在时返回 false（幂等，可安全重试）。
     *
     * <p>{@code joinedAt} 由调用方传入而不是在实现里 {@code now()}：库里存的是
     * <b>不带时区</b>的墙上时间，它的含义由 {@code tm.time.zone} 定义，
     * 而仓储层看不到那个 Bean（存储模块不依赖核心模块的装配）。
     * 让实现自己取 {@code LocalDateTime.now()} 等于悄悄改用 JVM 默认时区，
     * 在 TZ=UTC 的容器里就会与消息的 {@code created_at} 差 8 小时——
     * 而「差 8 小时」在跨时区部署里表现为「某人总是提前 8 小时发言」。
     */
    boolean addMember(long convId, long actorId, com.tm.im.domain.enums.MemberRole role,
                      java.time.LocalDateTime joinedAt);

    /**
     * 移除成员（§4.9 的踢人与退群共用）。
     *
     * @return 真的删掉了一行；{@code false} 表示他本来就不在群里（并发下两次踢同一人时，
     *         后一个拿到 false）。是否把 false 当成错误由调用方决定：重复的「移出」是一个
     *         已经成立的心愿，而「设置角色」不是——所以两处的处理不一样。
     */
    boolean removeMember(long convId, long actorId);

    /**
     * 改成员角色（§4.9 的 {@code PATCH .../members/{{actor_id}}}）。
     *
     * <p>刻意<b>不</b>在这里校验「谁有权给谁改角色」：那要读会话、读操作者的角色，
     * 是核心模块的规则（{@code ConversationService}）。仓储只提供「改这一行」这一个动作，
     * 规则只有一处，不会出现「REST 能改、别的入口不能改」。
     *
     * @return 命中了一行；{@code false} 表示这个人在这个会话里没有成员行
     */
    boolean updateMemberRole(long convId, long actorId, com.tm.im.domain.enums.MemberRole role);

    /**
     * 转让群主 —— <b>三条写入必须在同一个事务里</b>：
     * <ol>
     *   <li>{@code conversation.owner_actor} 指向新群主；</li>
     *   <li>新群主的成员行 {@code role=1}；</li>
     *   <li>旧群主的成员行 {@code role=2}（降为 ADMIN，见 03-rest-api.md §4.9 的取舍）。</li>
     * </ol>
     *
     * <p><b>为什么必须原子</b>：这三行合起来才是「群里恰有一个 OWNER」这条不变式。
     * 只写前两行时群里有<b>两个</b> OWNER（旧群主仍留着 1），而权限判据是成员行的角色，
     * 于是两个人都能转让、都能踢掉对方，且任何一种顺序都无法收敛。
     * 只写第一行则更糟：{@code owner_actor} 说 A 是群主、成员行说 B 是群主，
     * 而「谁能退群」（40306）读前者、「谁能改角色」读后者——表现为「群主退了，群却没主」。
     *
     * <p>条件式更新（{@code WHERE ... AND role = 1}）而不是无条件 SET：并发的两次转让里
     * 只有一次能改到行。第二次拿到 0 行影响数 → 返回 false，调用方据此回 40305
     * （「你已经不是群主了」），而不是把新群主又降成 ADMIN。
     *
     * @return 是否真的转让了；{@code false} 表示 {@code fromActorId} 当前不是这个群的群主
     */
    boolean transferOwnership(long convId, long fromActorId, long toActorId);

    /**
     * 改群名（§4.9 的 {@code PATCH /v1/conversations/{{conv_id}}}）。
     *
     * <p>库里的 {@code conversation} 没有「群公告」列，所以「改群名/公告」目前只有群名这一半
     * （README 里那条已知差异的后续：真做公告时它也是一个可改字段，走的正是本方法旁边的位置）。
     *
     * @return 命中了一行；{@code false} 表示会话不存在（调用方已在更早的步骤查过，正常不会出现）
     */
    boolean updateTitle(long convId, String title);

    /** 幂等：重复调用与调用一次等价。 */
    void updateLastReadSeq(long convId, long actorId, long lastReadSeq);

    Optional<ConversationMember> findMember(long convId, long actorId);

    boolean isMember(long convId, long actorId);

    /**
     * 拉某人的全部会话成员关系（会话行 + 我在其中的关系行），供「我的会话列表」使用。
     *
     * <p><b>刻意不在这里排序</b>：文档要求按「最近活跃」排，而活跃时间在消息表里
     * （{@code conversation} 没有 {@code updated_at} 列），只有调用方拿到消息才能算。
     * 这里返回的顺序是无意义的，调用方必须自己排——把「大致有序」交给实现
     * 会让一个漏排序的调用方得到一个「看起来对、偶尔错」的列表。
     *
     * <p><b>也不在 SQL 里加 LIMIT</b>（{@code maxScan} 只是给调用方的保险丝，
     * 见其参数的说明）：排在后面的会话可能是「一年没说话但今天刚活跃」的那个，
     * 用 LIMIT 截断会让它静默地从列表里消失，而用户会以为消息丢了。
     *
     * @param maxScan 最多返回多少行；{@code <= 0} 表示不限（正常取值）
     */
    List<ConversationMembership> listMemberships(long actorId, int maxScan);

    List<Long> listMemberIds(long convId, int limit);

    /**
     * 会话成员行（含角色、加入时间、未读游标），按加入时间升序，用于会话详情。
     *
     * <p>与 {@link #listMemberIds} 的区别是「要展示信息」还是「只要 ID」：
     * 前者要带上 {@code role}/{@code joined_at} 才能渲染成员列表，
     * 后者只服务于扇出与好友校验这类只要 ID 的路径。
     */
    List<ConversationMember> listMembers(long convId, int limit);

    /** 成员数。建群上限校验与「群里有几个人」的展示都要用。 */
    long countMembers(long convId);

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
