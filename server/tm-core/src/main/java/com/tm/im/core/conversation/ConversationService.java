package com.tm.im.core.conversation;

import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.common.id.IdGenerator;
import com.tm.im.common.json.Json;
import com.tm.im.core.identity.ActorLookup;
import com.tm.im.core.message.MessageCommandPort;
import com.tm.im.core.message.MessageService;
import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.entity.Conversation;
import com.tm.im.domain.entity.ConversationMember;
import com.tm.im.domain.entity.Message;
import com.tm.im.domain.enums.ConvType;
import com.tm.im.domain.enums.MemberRole;
import com.tm.im.domain.enums.MessageType;
import com.tm.im.domain.repository.ConversationMembership;
import com.tm.im.domain.repository.ConversationRepository;
import com.tm.im.domain.repository.MessageRepository;
import com.tm.im.domain.repository.ActorRepository;
import com.tm.im.domain.support.PairKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 会话与消息的查询/创建 —— 03-rest-api.md §4.1–§4.8 的实现。
 *
 * <p><b>它与 {@link MessageService} 的分工</b>：写一条消息、上报已读、SYNC 续传的
 * <b>规则</b>在 {@code MessageService}（长连接与 REST 共用同一条路径，错误码只有一处），
 * 这里只做「REST 需要的那些读法与用法」：拉会话列表、拉会话详情、按 seq 分页拉历史、
 * 建会话。凡是涉及消息写入的地方都<b>转调</b> {@code MessageService}，
 * 因此不会出现「REST 能发、长连接不能发」这类两套规则的问题。
 *
 * <p><b>会话列表为什么是 O(我的会话数) 次查询，以及为什么必须先这么写</b>：
 * 文档要求列表按「最近活跃」排序（§4.3），而活跃时间在<b>消息表</b>里——
 * {@code conversation} 没有 {@code updated_at} 列（见 {@code deploy/sql/01-schema.sql}），
 * 所以每个会话都要去它自己的分片上取一次「最新一条」（{@code ORDER BY seq DESC LIMIT 1}，
 * 走主键 {@code (conv_id, seq)} 的末端下降，是这一趟里最便宜的一种查询）。
 *
 * <p>三条看起来更省事、实际都是错的写法：
 * <ol>
 *   <li><b>先按 {@code joined_at} 取 50 个会话再在内存里排。</b>
 *       排在后面但今天刚活跃的会话会直接从列表里消失——用户会以为消息丢了。</li>
 *   <li><b>用 {@code conversation.seq_counter} 当 last_seq。</b>
 *       那个列只在 Redis 不可用时才被更新（见 {@code ConversationRepository#nextSeq}），
 *       平时<b>恒为 0</b>，于是所有会话的未读数都是 0。</li>
 *   <li><b>给 {@code conversation_member} 加一列 {@code last_activity_at} 并建索引。</b>
 *       这才是规模和性能上都对的答案，但它要改 DDL、重新生成实体、
 *       并在已建好的库上迁移——本轮刻意不做（见 DESIGN §14.1 的待办），
 *       所以这里选择了「先正确、再优化」：现在的写法对所有输入都给出正确答案，
 *       代价是会话多的人多花几趟查询（超过 {@link #LIST_COST_WARN_THRESHOLD} 会打 WARN）。
 *   </li>
 * </ol>
 *
 * <p><b>游标不是偏移量</b>：会话列表按活跃时间分页，意味着「翻页期间刚发过消息的那个会话
 * 会跳到第一页」。用 offset 的话第二次请求会跳过一条（第 1 页的最后一条被挤到第 2 页），
 * 而用「比这一项更旧」的游标语义时，最坏结果是同一条被看到两次——客户端按 conv_id 去重即可。
 * 「看到两次」比「静默漏掉一条消息」可接受得多。
 *
 * <p><b>本类不做限流、不做权限缓存</b>：那些在 M4 的清单里，现在每读一次都回源数据库——
 * 与「先把语义做对」一致（见 DESIGN §14.1）。
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    /**
     * 单聊固定两人，这里多取一名是为了发现「单聊里有第三个人」这种数据异常。
     *
     * <p>用法与 {@code MessageService.friendshipWithPeer} 一致：那边要报错
     * （发消息时「谁是对方」没有定义就不能发），这里只记 ERROR 并让列表可用——
     * 一个损坏的会话不该让整个会话列表打不开。
     */
    private static final int DIRECT_MEMBER_PROBE = 3;

    /** 会话数超过它就在列表路径上打一条 WARN（见类注释的成本说明）。 */
    private static final int LIST_COST_WARN_THRESHOLD = 200;

    /** 群名长度上限，与 {@code conversation.title VARCHAR(128)} 一致。 */
    private static final int MAX_TITLE_LENGTH = 128;

    /** 会话列表排序：活跃时间倒序，同一毫秒内按 convId 倒序（与游标的键完全一致）。 */
    private static final Comparator<Row> ROW_DESC = Comparator
            .comparingLong(Row::activityMillis).reversed()
            .thenComparing(row -> row.conversation().getId(), Comparator.reverseOrder());

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ActorRepository actors;
    private final ActorLookup actorLookup;
    private final MessageCommandPort messageCommands;
    private final IdGenerator idGenerator;
    private final ConversationProperties properties;
    private final ZoneId databaseZone;

    public ConversationService(ConversationRepository conversations,
                               MessageRepository messages,
                               ActorRepository actors,
                               ActorLookup actorLookup,
                               MessageCommandPort messageCommands,
                               IdGenerator idGenerator,
                               ConversationProperties properties,
                               ZoneId databaseZone) {
        this.conversations = conversations;
        this.messages = messages;
        this.actors = actors;
        this.actorLookup = actorLookup;
        this.messageCommands = messageCommands;
        this.idGenerator = idGenerator;
        this.properties = properties;
        this.databaseZone = databaseZone;
    }

    // ================================================================== 建会话

    /**
     * 获取或创建单聊会话（03-rest-api.md §4.1）。
     *
     * @param created true 表示这次调用真的建了会话；false 表示复用了（幂等）
     */
    public record DirectOutcome(Conversation conversation, Actor peer, boolean created, long lastSeq) {
    }

    /**
     * <p><b>刻意不要求双方是好友</b>：§4.1 的错误表里没有 40003，而「非好友不能发消息」
     * 是<b>发送</b>的规则（DESIGN §11.6）。建一个空会话不会把任何内容送给对方，
     * 而拒绝它会让「先点开会话、再决定说什么」这种正常交互变成必须先加好友——
     * 而加好友本身也要先找到这个人。真正需要拦的是发送，且只在那里拦。
     *
     * <p>幂等由 {@code uk_pair_key} 兜底而不是「先查再插」：并发下两个请求会同时查不到，
     * 唯一索引才是真正的防线（撞了就把对方刚建的那条读回来）。
     */
    public DirectOutcome openDirect(long actorId, String peerRef) {
        Actor peer = actorLookup.require(peerRef);
        if (PairKeys.isSelf(actorId, peer.getId())) {
            // 自己和自己聊天没有定义（默认要「对方」的字段全是自己），
            // 也不该在库里多出一条 pair_key=min_max 相同的会话。
            throw new TmException(ErrorCode.SELF_OPERATION, "不能和自己建单聊会话 actorId=" + actorId);
        }
        String pairKey = PairKeys.directConversationKey(actorId, peer.getId());
        LocalDateTime now = LocalDateTime.now(databaseZone);

        Conversation conversation = conversations.findDirectByPairKey(pairKey).orElse(null);
        boolean created = false;
        if (conversation == null) {
            Conversation fresh = new Conversation();
            fresh.setId(idGenerator.nextId());
            fresh.setConvType(ConvType.DIRECT);
            fresh.setPairKey(pairKey);
            fresh.setCreatedAt(now);
            try {
                conversation = conversations.insert(fresh);
                created = true;
            } catch (DuplicateKeyException e) {
                conversation = conversations.findDirectByPairKey(pairKey)
                        .orElseThrow(() -> new TmException(ErrorCode.DATABASE_UNAVAILABLE,
                                "并发建单聊后回查不到 pairKey=" + pairKey));
                log.debug("并发建单聊，复用已存在的那条 convId={} pairKey={}",
                        conversation.getId(), pairKey);
            }
        }
        // 成员行每条都补一次：复用已有会话时也可能缺成员（历史上只写了一半、
        // 或对方是通过别的方式进来的）。addMember 幂等，重复调用是安全的。
        conversations.addMember(conversation.getId(), actorId, MemberRole.MEMBER, now);
        conversations.addMember(conversation.getId(), peer.getId(), MemberRole.MEMBER, now);

        return new DirectOutcome(conversation, peer, created, messages.maxSeq(conversation.getId()));
    }

    /** 成员 + 该成员的账号资料（列表/详情都要展示 handle 与昵称）。 */
    public record ConversationMemberInfo(ConversationMember member, Actor actor) {
    }

    /**
     * 建群（03-rest-api.md §4.2）。
     *
     * @param systemMessageSent 系统消息是否写成功——<b>失败不影响建群结果</b>，见 {@link #announceGroupCreated}
     */
    public record GroupOutcome(Conversation conversation, List<ConversationMemberInfo> members,
                              boolean systemMessageSent) {
    }

    /**
     * 建群。
     *
     * <p><b>校验全部前置</b>：成员解析（可能回 40002/40004/40401）与人数上限都在
     * 写入第一行之前完成。建群的写入是「一个会话行 + N 个成员行」，一旦开始写就
     * 已经产生了用户可见的副作用；把可能失败的校验放在中间，就会出现
     * 「群建好了、但请求返回了错误」的状态，而客户端会重试并建出第二个群。
     *
     * <p><b>群成员不必互为好友</b>（DESIGN §11.6）：3 人群要求 3 对好友关系的话，
     * 群聊这个功能就不成立了。
     */
    public GroupOutcome createGroup(long actorId, String title, List<String> memberRefs) {
        String cleanTitle = requireTitle(title);
        List<String> refs = memberRefs == null ? List.of() : memberRefs;
        if (refs.isEmpty()) {
            throw new TmException(ErrorCode.MISSING_PARAMETER, "members 不能为空（群里除了自己至少要有一个人）");
        }
        int maxMembers = properties.getMaxGroupMembers();
        if (refs.size() + 1 > maxMembers) {
            // 用「请求里的个数」先判一次，避免为了解析 1000 个 handle 先打 1000 次库。
            throw new TmException(ErrorCode.GROUP_MEMBER_LIMIT,
                    "成员数 " + (refs.size() + 1) + " 超过单群上限 " + maxMembers);
        }

        // 用 LinkedHashSet 去重并保持请求顺序：同一个 handle 写两遍不该变成「两个成员」，
        // 也不该报错（那是客户端复制粘贴的常见结果，重试也修不好）。
        LinkedHashSet<Long> memberIds = new LinkedHashSet<>();
        for (String ref : refs) {
            Actor actor = actorLookup.require(ref);
            if (actor.getId() == actorId) {
                continue;   // 建群者自己已经占了 OWNER 那一行
            }
            memberIds.add(actor.getId());
        }
        if (memberIds.isEmpty()) {
            throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "members 里除了自己之外没有别人，群聊至少要两个成员");
        }
        if (memberIds.size() + 1 > maxMembers) {
            // 去重后才可能发现真实人数（上面那次是拿「请求里的个数」粗筛）
            throw new TmException(ErrorCode.GROUP_MEMBER_LIMIT,
                    "去重后成员数 " + (memberIds.size() + 1) + " 超过单群上限 " + maxMembers);
        }

        long convId = idGenerator.nextId();
        LocalDateTime now = LocalDateTime.now(databaseZone);
        Conversation conversation = new Conversation();
        conversation.setId(convId);
        conversation.setConvType(ConvType.GROUP);
        conversation.setTitle(cleanTitle);
        conversation.setOwnerActor(actorId);
        conversation.setCreatedAt(now);
        // pair_key 保持 NULL：uk_pair_key 对 NULL 不去重（MySQL 唯一索引允许多个 NULL），
        // 这正是群聊需要的语义。给群聊也填一个去重键会让「同一个群名」变成唯一的。

        List<ConversationMember> rows = new ArrayList<>();
        rows.add(memberRow(convId, actorId, MemberRole.OWNER, now));
        for (long memberId : memberIds) {
            rows.add(memberRow(convId, memberId, MemberRole.MEMBER, now));
        }
        conversations.createGroup(conversation, rows);

        boolean announced = announceGroupCreated(conversation, actorId);

        Map<Long, Actor> byId = loadActors(rows.stream().map(ConversationMember::getActorId).toList());
        List<ConversationMemberInfo> infos = rows.stream()
                .map(row -> new ConversationMemberInfo(row, byId.get(row.getActorId())))
                .toList();
        return new GroupOutcome(conversation, infos, announced);
    }

    /**
     * 建群后写一条 SYSTEM 消息（DESIGN §11.2）。
     *
     * <p><b>失败不回滚、也不让请求失败</b>：这条消息是通知，不是事实——群已经在库里了。
     * 若让它决定成败，客户端会因为一条通知写入失败而重试建群，于是得到两个群；
     * 而丢一条「某人建了群」的通知，用户看到的只是这个群的第一条消息是空的。
     * 两害相权，取「记得留下 ERROR 日志」。
     *
     * <p>它同时是唯一一处<b>服务端自己产生消息</b>的地方（{@code fromClient=false}）：
     * 客户端不许发 SYSTEM 这条规则在 {@code MessageService.validate} 里，
     * 这里正是它的合法使用者。
     */
    private boolean announceGroupCreated(Conversation conversation, long ownerId) {
        try {
            messageCommands.send(new MessageService.SendCommand(
                    conversation.getId(),
                    ownerId,
                    null,                       // 服务端消息不需要幂等键：它不来自会重试的客户端
                    MessageType.SYSTEM,
                    Json.write(Map.of("action", "group_created", "actor_id", ownerId)),
                    0,
                    false));
            return true;
        } catch (RuntimeException e) {
            log.error("建群系统消息写入失败 convId={}（群已建成，消息只是通知）", conversation.getId(), e);
            return false;
        }
    }

    // ================================================================== 读会话

    /** 会话列表的一项。{@code peer} 仅单聊有；{@code memberCount} 群聊为精确值（COUNT）。 */
    public record ConversationSummary(Conversation conversation,
                                      Actor peer,
                                      long lastSeq,
                                      long lastReadSeq,
                                      Message lastMessage,
                                      boolean muted,
                                      long memberCount,
                                      LocalDateTime updatedAt) {

        /** 未读数 = 会话最新 seq − 我的已读游标（服务端算好，客户端不必自己减，见 §4.3）。 */
        public long unreadCount() {
            return Math.max(0, lastSeq - lastReadSeq);
        }
    }

    /** 分页结果。{@code nextCursor} 为 null 表示没有更多。 */
    public record Page<T>(List<T> items, String nextCursor, boolean hasMore) {
    }

    /**
     * 我的会话列表（§4.3），按最近活跃倒序。
     *
     * @param cursor 上一页返回的 {@code next_cursor}；为空表示第一页
     */
    public Page<ConversationSummary> listConversations(long actorId, int limit, String cursor) {
        int pageSize = properties.clampPageSize(limit);
        PageCursors.ConversationCursor from =
                cursor == null || cursor.isBlank() ? null : PageCursors.decodeConversation(cursor);

        List<ConversationMembership> memberships = conversations.listMemberships(actorId, 0);
        if (memberships.size() > LIST_COST_WARN_THRESHOLD) {
            // 这条 WARN 的意思是「该给 conversation_member 加 last_activity_at 了」，
            // 而不是「有人用得太狠」（类注释里有完整理由）。
            log.warn("会话列表按活跃排序的代价随会话数线性增长：actorId={} 会话数={}（查询数≈{}）",
                    actorId, memberships.size(), memberships.size() * 2 + 2);
        }

        List<Row> rows = new ArrayList<>(memberships.size());
        for (ConversationMembership membership : memberships) {
            rows.add(snapshot(membership, actorId));
        }
        rows.sort(ROW_DESC);

        List<Row> rest = from == null
                ? rows
                : rows.stream().filter(row -> isOlderThan(row, from)).toList();
        boolean hasMore = rest.size() > pageSize;
        List<Row> page = hasMore ? rest.subList(0, pageSize) : rest;

        Map<Long, Actor> peers = loadActors(page.stream().map(Row::peerId).filter(Objects::nonNull).toList());
        List<ConversationSummary> items = new ArrayList<>(page.size());
        for (Row row : page) {
            // 空集用的是不可变的 Map.of()，而它<b>不接受 null 键</b>（拒绝而非返回 null）。
            // 群聊行的 peerId 就是 null，所以这个判空不能省：少了它会以一个
            // 「只要列表里有群聊就 500」的形式暴露，而且只在「一页里全是群聊」时才出现。
            Long peerId = row.peerId();
            items.add(row.toSummary(peerId == null ? null : peers.get(peerId)));
        }
        String nextCursor = hasMore ? PageCursors.encodeConversation(
                page.get(page.size() - 1).activityMillis(),
                page.get(page.size() - 1).conversation().getId()) : null;
        return new Page<>(List.copyOf(items), nextCursor, hasMore);
    }

    /** 会话详情（§4.4）。{@code members} 最多 {@code tm.conversation.max-group-members} 条，{@code memberCount} 始终精确。 */
    public record ConversationDetail(Conversation conversation,
                                     ConversationMemberInfo me,
                                     List<ConversationMemberInfo> members,
                                     long memberCount,
                                     long lastSeq,
                                     long unreadCount) {
    }

    public ConversationDetail detail(long actorId, long convId) {
        Conversation conversation = requireConversation(convId);
        ConversationMember me = conversations.findMember(convId, actorId)
                .orElseThrow(() -> new TmException(ErrorCode.NOT_A_MEMBER,
                        "convId=" + convId + ", actorId=" + actorId));

        // 上限取「单群人数上限」而不是「默认页大小」：成员列表是要一次画完的，
        // 而默认页大小是给消息历史用的（50 条消息 vs 50 个成员完全不是一回事）。
        int memberLimit = properties.getMaxGroupMembers() + 1;
        List<ConversationMember> rows = conversations.listMembers(convId, memberLimit);
        Map<Long, Actor> byId = loadActors(rows.stream().map(ConversationMember::getActorId).toList());
        List<ConversationMemberInfo> infos = rows.stream()
                .map(row -> new ConversationMemberInfo(row, byId.get(row.getActorId())))
                .toList();

        long memberCount = conversations.countMembers(convId);
        long lastSeq = messages.maxSeq(convId);
        long lastRead = me.getLastReadSeq() == null ? 0L : me.getLastReadSeq();
        return new ConversationDetail(conversation,
                new ConversationMemberInfo(me, byId.get(actorId)),
                infos, memberCount, lastSeq, Math.max(0, lastSeq - lastRead));
    }

    // ================================================================== 读消息

    /**
     * 历史消息，按 seq <b>倒序</b>（最新在前），用于「上滑加载历史」（§4.6）。
     *
     * <p>{@code has_more} 用「多取一行」得出，与 SYNC 同一条依据（DESIGN §10.2）：
     * 先查 maxSeq 再比大小会多一次查询，而且两次之间新插一条消息就会把判断弄错。
     */
    public Page<Message> history(long actorId, long convId, int limit, String cursor) {
        int pageSize = properties.clampPageSize(limit);
        PageCursors.MessageCursor from =
                cursor == null || cursor.isBlank() ? null : PageCursors.decodeMessage(cursor);
        requireMember(convId, actorId);

        List<Message> rows = messages.listBeforeSeq(convId, from == null ? 0L : from.seq(), pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<Message> items = hasMore ? rows.subList(0, pageSize) : rows;
        if (items.isEmpty()) {
            // 空页只可能是「第一页且会话没有消息」或「游标已经比最早的还早」，
            // 两者都不该回 next_cursor（回了会让客户端死循环地拉空页）。
            return new Page<>(List.of(), null, false);
        }
        String nextCursor = hasMore
                ? PageCursors.encodeMessage(items.get(items.size() - 1).getSeq())
                : null;
        return new Page<>(List.copyOf(items), nextCursor, hasMore);
    }

    /**
     * 增量拉取（§4.7）：返回 {@code seq > sinceSeq} 的消息，按 seq 升序。
     *
     * <p><b>为什么与 SYNC 不同，这里不是「跳过」而是「报 40303」</b>：SYNC 一次带多个游标，
     * 跳过坏的那个能保住其余会话（客户端本地还留着退群前的游标，详见
     * {@code MessageService.sync}）；而这个接口只问一个会话，跳过等于回一个空列表，
     * 客户端会以为「拉到了，没有新消息」——把权限问题伪装成「没有消息」。
     */
    public record IncrementalOutcome(List<Message> items, long latestSeq, boolean hasMore) {
    }

    public IncrementalOutcome incremental(long actorId, long convId, long sinceSeq, int limit) {
        if (sinceSeq < 0) {
            throw new TmException(ErrorCode.INVALID_CURSOR, "since_seq 不能为负: " + sinceSeq);
        }
        requireMember(convId, actorId);
        int pageSize = properties.clampPageSize(limit);

        List<Message> rows = messages.listAfterSeq(convId, sinceSeq, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<Message> items = hasMore ? rows.subList(0, pageSize) : rows;
        return new IncrementalOutcome(List.copyOf(items), messages.maxSeq(convId), hasMore);
    }

    /** 已读上报的结果（§4.8）。{@code lastReadSeq} 是<b>生效后</b>的游标，见 {@link #markRead}。 */
    public record ReadOutcome(long convId, long lastReadSeq, long unreadCount) {
    }

    /**
     * 上报已读（§4.8）。
     *
     * <p>校验与写入都转调 {@link MessageService#markRead}（成员身份、非负、不得超过当前最大 seq），
     * 但返回值<b>重新读一次成员行</b>而不是用请求里的值：仓储的更新是「游标只前进」的
     * （乱序到达的旧请求不该让未读数变大），所以客户端上报 3 而当前已是 7 时，
     * 请求值是 3 而生效值是 7。回请求值会让响应里的 {@code unread_count} 凭空变大——
     * 而那正是用户唯一会看到的那个数字。
     */
    public ReadOutcome markRead(long actorId, long convId, long lastReadSeq) {
        messageCommands.markRead(convId, actorId, lastReadSeq);
        long effective = conversations.findMember(convId, actorId)
                .map(ConversationMember::getLastReadSeq)
                .orElse(0L);
        long lastSeq = messages.maxSeq(convId);
        return new ReadOutcome(convId, effective, Math.max(0, lastSeq - effective));
    }

    // ================================================================== 群成员管理（§4.9）

    /*
     * 权限矩阵（03-rest-api.md §4.9 的代码侧）：
     *
     *   操作         | 谁能做              | 不能时的码
     *   -------------|---------------------|----------------------------
     *   加人         | ADMIN / OWNER       | 40303（非成员）/ 40305（MEMBER）
     *   踢人         | OWNER 任意；ADMIN 只能踢比自己低的（MEMBER） | 40305
     *   退群         | 任何人（OWNER 除外）| 40303 / 40306
     *   改群名       | ADMIN / OWNER       | 40305
     *   改角色/转让  | 仅 OWNER            | 40305
     *
     * 这套规则里有三个必须钉住的取舍（写下来是因为它们都是「加上去容易、改掉很难」那一类）：
     *
     * ① **只有 OWNER 能给别人改角色**。若 ADMIN 也能，那么「OWNER 指定的管理员」会被
     *    另一个管理员撤掉——OWNER 的选择就不再是最终的，角色这个字段也就不再有意义。
     *    另外这也顺手消掉了一个活锁：两个 ADMIN 互相降级，任何一方都无法把状态推到稳定。
     *
     * ② **「比自己的角色高」是唯一的判断式**（{@link MemberRole} 的码值是「越小越有权」）。
     *    OWNER=1 于是「没人能踢 OWNER」不需要单独写一条规则——没有任何角色码小于 1。
     *    同一个式子也给出了「两个 ADMIN 互相踢不动」，不需要额外枚举特殊情况。
     *
     * ③ **转让把旧群主降为 ADMIN，而不是 MEMBER**。「转让」交出去的只是身份，
     *    转让者并没有说自己从此不管这个群；而「降为 MEMBER」是一个**独立**的动作
     *    （新群主可以立刻做）。合并在一次调用里的代价是：旧群主想反悔时发现自己
     *    连踢人的权限都没了，而那个人（群里唯一的 OWNER）可能是刚被拉进来的。
     */

    /** 加人（§4.9）。{@code alreadyMembers} 是「本来就在群里、被跳过」的那批人。 */
    public record AddOutcome(long convId,
                             List<ConversationMemberInfo> added,
                             List<Long> alreadyMembers,
                             long memberCount) {
    }

    /** 踢人与退群的共同结果：都是「这个会话里少了一个人」。 */
    public record RemoveOutcome(long convId, long actorId, long memberCount) {
    }

    /** 改角色／转让群主的结果。{@code ownerActor} 是<b>生效后</b>的群主。 */
    public record RoleOutcome(long convId, long actorId, MemberRole role, long ownerActor,
                              long memberCount) {
    }

    /** 改群名的结果。 */
    public record TitleOutcome(long convId, String title) {
    }

    /**
     * 加人（§4.9 的 {@code POST /v1/conversations/{{conv_id}}/members}）。
     *
     * <p><b>「已经在群里」不报错</b>（因此永远不会返回 40905）：这个接口收的是一批人，
     * 其中几个已在群里不该让另外几个也加不进去；而「一个都没加进去」也不是失败
     * （重复调用与调用一次等价）。被跳过的人从 {@code already_members} 里回给客户端，
     * 它据此刷新成员列表即可。
     *
     * <p><b>不要求与邀请人是好友</b>（DESIGN §11.6）：建群都不要求成员互为好友，
     * 后续加人却要求的话，换来的只是「想拉个人得先加好友，而加好友又要先找到这个人」。
     */
    public AddOutcome addMembers(long actorId, long convId, List<String> memberRefs) {
        GroupContext ctx = requireGroup(convId, actorId);
        requireAdminOrOwner(ctx, "加人");

        List<String> refs = memberRefs == null ? List.of() : memberRefs;
        if (refs.isEmpty()) {
            throw new TmException(ErrorCode.MISSING_PARAMETER, "members 不能为空");
        }
        int maxMembers = properties.getMaxGroupMembers();
        List<Long> current = conversations.listMemberIds(convId, maxMembers + 1);
        if (refs.size() > maxMembers) {
            // 粗筛，但必须是<b>真的上界</b>：一次加的人比单群上限还多，无论其中多少已经在群里
            // 都不可能成立，所以这一步可以先行（它省掉的是「为一万个 handle 逐个打库」）。
            // 不能拿 `current.size() + refs.size()` 当判据：这里允许重复（已在群里的人会被跳过），
            // 于是一个「一个新人 + 一个已在群里的人」的请求会在满员群里被误拒——而那是客户端的
            // 成员列表过期，不是它要加太多人。真实人数只能在解析、去重之后算（见下面那一次）。
            throw new TmException(ErrorCode.GROUP_MEMBER_LIMIT,
                    "一次加入 " + refs.size() + " 人超过单群上限 " + maxMembers);
        }

        // 解析（可能 40002 / 40004 / 40401）全部前置：一行都不写之前完成。
        // 理由与建群同一个：开始写之后才失败，会留下「加进去一半」的群，而客户端会重试。
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (String ref : refs) {
            ids.add(actorLookup.require(ref).getId());
        }

        Set<Long> members = new HashSet<>(current);
        List<Long> toAdd = new ArrayList<>();
        List<Long> already = new ArrayList<>();
        for (Long id : ids) {
            if (members.contains(id)) {
                already.add(id);
            } else {
                toAdd.add(id);
            }
        }
        if (current.size() + toAdd.size() > maxMembers) {
            // 真实的检查：解析与去重之后，真正会新增几个人才是确定的
            throw new TmException(ErrorCode.GROUP_MEMBER_LIMIT,
                    "加入 " + toAdd.size() + " 人后成员数将达 "
                            + (current.size() + toAdd.size()) + "，超过单群上限 " + maxMembers);
        }

        LocalDateTime now = LocalDateTime.now(databaseZone);
        for (long id : toAdd) {
            conversations.addMember(convId, id, MemberRole.MEMBER, now);
        }

        Map<Long, Actor> byId = loadActors(toAdd);
        List<ConversationMemberInfo> added = new ArrayList<>(toAdd.size());
        for (long id : toAdd) {
            added.add(new ConversationMemberInfo(memberRow(convId, id, MemberRole.MEMBER, now), byId.get(id)));
            // 通知在写入之后：新成员这时才在成员表里，扇出才会把它算进去
            // （他自己的客户端也才能收到「X 邀请你加入群聊」）。
            announce(convId, actorId, "member_joined", byId.get(id), Map.of());
        }
        return new AddOutcome(convId, List.copyOf(added), List.copyOf(already),
                conversations.countMembers(convId));
    }

    /**
     * 踢人（§4.9 的 {@code DELETE /v1/conversations/{{conv_id}}/members/{{actor_id}}}）。
     *
     * <p><b>目标不在群里回 40908 而不是「成功」</b>：重复的「移出」看起来像幂等，
     * 但这里刻意不这么做——一个拼错的 actor_id 会静默地「成功移除」，而客户端以为自己
     * 刚刚踢掉了一个人。40908 的客户端动作是「刷新成员列表」，并发踢同一人时后到的那个
     * 拿到的正是它，两边都是同一个处理。
     */
    public RemoveOutcome removeMember(long actorId, long convId, long targetId) {
        GroupContext ctx = requireGroup(convId, actorId);
        if (targetId == actorId) {
            throw new TmException(ErrorCode.SELF_OPERATION,
                    "不能踢自己：退群用 DELETE /v1/conversations/" + convId + "/members/me");
        }
        ConversationMember target = conversations.findMember(convId, targetId)
                .orElseThrow(() -> new TmException(ErrorCode.TARGET_NOT_MEMBER,
                        "actorId=" + targetId + " 不是 convId=" + convId + " 的成员"));
        requireCanActOn(ctx, target, "踢人");

        conversations.removeMember(convId, targetId);
        announce(convId, actorId, "member_removed", loadActors(List.of(targetId)).get(targetId), Map.of());
        return new RemoveOutcome(convId, targetId, conversations.countMembers(convId));
    }

    /**
     * 退群（§4.9 的 {@code DELETE /v1/conversations/{{conv_id}}/members/me}）。
     *
     * <p><b>通知先写、成员行后删</b>——与其余几个接口相反，理由是硬的：
     * {@code MessageService} 要求 SYSTEM 消息的作者是会话成员（否则「客户端伪造系统消息」
     * 那条防护就没了），而我一旦退群就不再是成员，那条消息就再也写不进去。
     * 代价是「通知已写、删除失败」时历史里会多一句「X 退出了群聊」，而成员行还在——
     * 这种情形只会出现在数据库报错时，且重试就能收敛。反过来（先删后写）的代价是
     * 那条通知<b>永远丢失</b>，因为重试仍然是先删。
     */
    public RemoveOutcome leaveGroup(long actorId, long convId) {
        GroupContext ctx = requireGroup(convId, actorId);
        if (ctx.me().getRole() == MemberRole.OWNER) {
            throw new TmException(ErrorCode.OWNER_CANNOT_LEAVE,
                    "群主不能直接退群：先 PATCH /v1/conversations/" + convId
                            + "/members/{actor_id} {\"role\":1} 把群主转让出去");
        }
        announce(convId, actorId, "member_left", loadActors(List.of(actorId)).get(actorId), Map.of());
        conversations.removeMember(convId, actorId);
        return new RemoveOutcome(convId, actorId, conversations.countMembers(convId));
    }

    /**
     * 改角色／转让群主（§4.9 的 {@code PATCH /v1/conversations/{{conv_id}}/members/{{actor_id}}}）。
     *
     * <p>{@code role=1} 是<b>转让群主</b>，语义是「我是群主，现在群主是 TA」——见
     * {@link #transfer}：它是一个原子动作（新群主 upgraded、旧群主降为 ADMIN、
     * {@code conversation.owner_actor} 重指）。{@code role=2/3} 是普通的角色设置。
     *
     * <p><b>同值重复上报是幂等的</b>（不写库、也不产生系统消息）：客户端按
     * 「当前角色」回填下拉框，点一下确定往往就是同一个值。
     */
    public RoleOutcome changeRole(long actorId, long convId, long targetId, int roleCode) {
        GroupContext ctx = requireGroup(convId, actorId);
        requireOwner(ctx, "设置角色");

        MemberRole requested = MemberRole.of(roleCode);
        if (requested == null) {
            throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "role 只能是 1(OWNER) / 2(ADMIN) / 3(MEMBER): " + roleCode);
        }
        if (targetId == actorId) {
            // 改自己的角色 = 让群失去群主（1）或降自己的权（2/3），两者都没有定义。
            throw new TmException(ErrorCode.SELF_OPERATION, "不能改自己的角色 actorId=" + actorId);
        }
        ConversationMember target = conversations.findMember(convId, targetId)
                .orElseThrow(() -> new TmException(ErrorCode.TARGET_NOT_MEMBER,
                        "actorId=" + targetId + " 不是 convId=" + convId + " 的成员"));

        if (requested == MemberRole.OWNER) {
            return transfer(ctx, target);
        }
        if (target.getRole() == requested) {
            // 幂等：目标状态已经成立，不写库、不发通知，但仍然回当前的真实值
            return new RoleOutcome(convId, targetId, requested,
                    ctx.conversation().getOwnerActor(), conversations.countMembers(convId));
        }
        conversations.updateMemberRole(convId, targetId, requested);
        announce(convId, actorId, "member_role_changed", loadActors(List.of(targetId)).get(targetId),
                Map.of("role", requested.code()));
        return new RoleOutcome(convId, targetId, requested,
                ctx.conversation().getOwnerActor(), conversations.countMembers(convId));
    }

    /** 转让群主：见 {@link #changeRole} 与仓储方法的注释。 */
    private RoleOutcome transfer(GroupContext ctx, ConversationMember target) {
        long convId = ctx.conversation().getId();
        long actorId = ctx.me().getActorId();
        if (!conversations.transferOwnership(convId, actorId, target.getActorId())) {
            // 仓储的条件式更新没命中：并发下另一个请求已经把群主转让出去了。
            throw new TmException(ErrorCode.NO_PRIVILEGE,
                    "转让失败：actorId=" + actorId + " 已经不是 convId=" + convId + " 的群主");
        }
        announce(convId, actorId, "owner_transferred", loadActors(List.of(target.getActorId()))
                .get(target.getActorId()), Map.of());
        return new RoleOutcome(convId, target.getActorId(), MemberRole.OWNER, target.getActorId(),
                conversations.countMembers(convId));
    }

    /**
     * 改群名（§4.9 的 {@code PATCH /v1/conversations/{{conv_id}}}）。
     *
     * <p>群公告没有做：{@code conversation} 表里没有那一列（见 README 里那条已知差异），
     * 而为了一个字段改 DDL + 重新生成实体 + 迁移，属于另一件事。
     */
    public TitleOutcome renameGroup(long actorId, long convId, String title) {
        GroupContext ctx = requireGroup(convId, actorId);
        requireAdminOrOwner(ctx, "改群名");
        String clean = requireTitle(title);
        if (Objects.equals(clean, ctx.conversation().getTitle())) {
            return new TitleOutcome(convId, clean);   // 幂等：没有变化就不写、也不发通知
        }
        conversations.updateTitle(convId, clean);
        announce(convId, actorId, "title_changed", null, Map.of("title", clean));
        return new TitleOutcome(convId, clean);
    }

    /**
     * §4.9 的公共前置：<b>会话存在（40402）→ 我是成员（40303）→ 它是群聊（40302）</b>。
     *
     * <p>顺序沿用 {@link #requireMember}（先 40402 再 40303），只多在末尾加一条「不是群聊」：
     * 单聊没有「群内权限」这个概念，也没有群名，所以这五个接口对单聊一律不可用。
     * 用 40302 而不是 40002，是因为请求本身完全合法——是<b>这个资源</b>不能被这么操作，
     * 客户端的动作也确实是「确认 conv_type」而不是「改参数」。
     */
    private GroupContext requireGroup(long convId, long actorId) {
        Conversation conversation = requireConversation(convId);
        ConversationMember me = conversations.findMember(convId, actorId)
                .orElseThrow(() -> new TmException(ErrorCode.NOT_A_MEMBER,
                        "convId=" + convId + ", actorId=" + actorId));
        if (conversation.getConvType() != ConvType.GROUP) {
            throw new TmException(ErrorCode.PERMISSION_DENIED,
                    "convId=" + convId + " 不是群聊，没有成员管理/群名（conv_type="
                            + (conversation.getConvType() == null ? "null" : conversation.getConvType().code()) + "）");
        }
        return new GroupContext(conversation, me);
    }

    private record GroupContext(Conversation conversation, ConversationMember me) {
    }

    /** 加人／改群名：ADMIN 与 OWNER 都行。 */
    private static void requireAdminOrOwner(GroupContext ctx, String action) {
        MemberRole role = ctx.me().getRole();
        if (role == null || role == MemberRole.MEMBER) {
            throw new TmException(ErrorCode.NO_PRIVILEGE,
                    action + "需要 ADMIN 或 OWNER，当前角色=" + (role == null ? "null" : role.code()));
        }
    }

    /** 改角色／转让群主：只有群主。理由见本节开头的取舍①。 */
    private static void requireOwner(GroupContext ctx, String action) {
        if (ctx.me().getRole() != MemberRole.OWNER) {
            throw new TmException(ErrorCode.NO_PRIVILEGE,
                    action + "只能由群主做，当前角色="
                            + (ctx.me().getRole() == null ? "null" : ctx.me().getRole().code()));
        }
    }

    /**
     * 「能不能动这个人」：只有一个判断式——<b>目标的角色码严格大于我的</b>。
     *
     * <p>OWNER(1) 于是可以动任何人（ADMIN=2、MEMBER=3 都大于 1），ADMIN(2) 只能动 MEMBER(3)，
     * 而「谁都不能动 OWNER」是同一个式子在这个编码下的自然推论（没有比 1 更小的角色码）。
     */
    private static void requireCanActOn(GroupContext ctx, ConversationMember target, String action) {
        MemberRole mine = ctx.me().getRole();
        MemberRole theirs = target.getRole();
        if (mine == null || theirs == null || theirs.code() <= mine.code()) {
            throw new TmException(ErrorCode.NO_PRIVILEGE,
                    action + "需要比目标更高的角色：我是 " + (mine == null ? "null" : mine.code())
                            + "，目标是 " + (theirs == null ? "null" : theirs.code())
                            + "（群主只能通过转让换人，不能被移除）");
        }
    }

    /**
     * 写一条成员管理产生的 SYSTEM 消息（DESIGN §11.2）。
     *
     * <p><b>失败不抛异常</b>，与 {@link #announceGroupCreated} 同一条取舍：这条消息是通知，
     * 不是事实——数据已经改了。让通知的失败回滚业务操作，会让用户看到「踢人失败」
     * 而那个人其实已经被踢掉了（刷新一下才发现），比丢一条通知难排查得多。
     *
     * <p><b>{@code content} 里带上 handle / display_name</b>：退群、被移出的人已经不在
     * 成员表里了，而客户端渲染「carol 退出了群聊」时要的正是这个名字——它只能从此处取。
     * 名字是<b>当时</b>的快照，与消息本身一样是历史事实。
     *
     * @param subject 事件当事人；改群名没有当事人，传 null
     */
    private void announce(long convId, long operatorId, String action, Actor subject,
                          Map<String, ?> extra) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("action", action);
        if (subject != null) {
            content.put("actor_id", subject.getId());
            content.put("handle", subject.getHandle());
            content.put("display_name", subject.getDisplayName());
        }
        content.putAll(extra);
        try {
            messageCommands.send(new MessageService.SendCommand(
                    convId, operatorId, null, MessageType.SYSTEM, Json.write(content), 0, false));
        } catch (RuntimeException e) {
            log.error("成员管理产生的系统消息写入失败 convId={} action={} operator={}"
                    + "（操作已生效，消息只是通知）", convId, action, operatorId, e);
        }
    }

    // ================================================================== 内部

    /**
     * 会话列表的一行。<b>时间先折成毫秒</b>再参与排序与游标编码：
     * 排序键与游标键必须是同一个值，否则「游标指向那一项」这件事在两次请求里会指向不同位置，
     * 分页就会重复或漏项（见 {@link PageCursors} 的类注释）。
     */
    private record Row(Conversation conversation,
                       ConversationMember member,
                       Message lastMessage,
                       Long peerId,
                       long memberCount,
                       LocalDateTime updatedAt,
                       long activityMillis) {

        long lastSeq() {
            return lastMessage == null || lastMessage.getSeq() == null ? 0L : lastMessage.getSeq();
        }

        long lastReadSeq() {
            return member.getLastReadSeq() == null ? 0L : member.getLastReadSeq();
        }

        ConversationSummary toSummary(Actor peer) {
            return new ConversationSummary(conversation, peer, lastSeq(), lastReadSeq(),
                    lastMessage, Boolean.TRUE.equals(member.getMuted()), memberCount, updatedAt);
        }
    }

    private Row snapshot(ConversationMembership membership, long actorId) {
        Conversation conversation = membership.conversation();
        Message last = messages.findLatest(conversation.getId()).orElse(null);

        Long peerId = null;
        long memberCount;
        if (conversation.getConvType() == ConvType.DIRECT) {
            List<Long> ids = conversations.listMemberIds(conversation.getId(), DIRECT_MEMBER_PROBE);
            memberCount = ids.size();
            List<Long> peers = ids.stream().filter(id -> id != actorId).toList();
            if (peers.size() == 1) {
                peerId = peers.get(0);
            } else {
                log.error("单聊会话成员数异常 convId={} members={}（期望 2 名）—— 该条没有 peer",
                        conversation.getId(), ids);
            }
        } else {
            memberCount = conversations.countMembers(conversation.getId());
        }

        LocalDateTime updatedAt = last != null && last.getCreatedAt() != null
                ? last.getCreatedAt()
                : conversation.getCreatedAt();
        return new Row(conversation, membership.member(), last, peerId, memberCount, updatedAt,
                epochMilli(updatedAt));
    }

    /**
     * 把库里的墙上时间折算成毫秒时间戳。
     *
     * <p>折算用 {@code databaseZone} 而不是 UTC：{@code DATETIME} 的含义由那个配置定义
     * （它必须与 sharding.yaml 的 serverTimezone 一致）。用错时区不会报错，
     * 只会让「最近活跃」的排序整体偏移——在一个所有人都在同一时区的部署里看不见，
     * 而跨时区时就变成「消息顺序看起来是乱的」。
     *
     * <p>没有时间（会话行 {@code created_at} 为空，只可能来自手工数据）时返回 0，
     * 于是它排在列表最后——比被当成「1970 年」更直白：两者效果相同，但 0 不会被误读成真实时间。
     */
    private long epochMilli(LocalDateTime time) {
        return time == null ? 0L : time.atZone(databaseZone).toInstant().toEpochMilli();
    }

    /** 游标语义：严格「比它更旧」的那一批（见类注释里对 offset 的取舍）。 */
    private static boolean isOlderThan(Row row, PageCursors.ConversationCursor cursor) {
        if (row.activityMillis() != cursor.updatedAtMillis()) {
            return row.activityMillis() < cursor.updatedAtMillis();
        }
        return row.conversation().getId() < cursor.convId();
    }

    /**
     * 会话存在 + 我是成员。
     *
     * <p>顺序与 {@code MessageService.send} 一致：先判会话存在（40402）再判成员身份（40303）。
     * 两个码各有用途（客户端对「会话不存在」该清本地缓存，对「不是成员」该退出这个会话），
     * 所以不能合并成一个「你没权限」。会话 id 是雪花号，不可枚举，因此这个区分
     * 不构成「猜出别人有哪些会话」的通道。
     */
    private void requireMember(long convId, long actorId) {
        Conversation conversation = requireConversation(convId);
        if (!conversations.isMember(conversation.getId(), actorId)) {
            throw new TmException(ErrorCode.NOT_A_MEMBER,
                    "convId=" + convId + ", actorId=" + actorId);
        }
    }

    private Conversation requireConversation(long convId) {
        if (convId <= 0) {
            throw new TmException(ErrorCode.INVALID_PARAMETER, "conv_id 必须为正整数: " + convId);
        }
        return conversations.findById(convId)
                .orElseThrow(() -> new TmException(ErrorCode.CONVERSATION_NOT_FOUND, "convId=" + convId));
    }

    private Map<Long, Actor> loadActors(Collection<Long> actorIds) {
        List<Long> distinct = actorIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, Actor> out = new HashMap<>();
        for (Actor actor : actors.findByIds(distinct)) {
            out.put(actor.getId(), actor);
        }
        return out;
    }

    private static ConversationMember memberRow(long convId, long actorId, MemberRole role,
                                                LocalDateTime joinedAt) {
        ConversationMember member = new ConversationMember();
        member.setConvId(convId);
        member.setActorId(actorId);
        member.setRole(role);
        member.setLastReadSeq(0L);
        member.setMuted(false);
        member.setJoinedAt(joinedAt);
        return member;
    }

    /**
     * 群名校验。
     *
     * <p>超长直接报错而不是截断：截断会让客户端回显的群名与库里的不同
     * （用户以为自己的群名叫全了），而「回显不一致」是最难被当成 bug 报告的一类问题。
     */
    private static String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new TmException(ErrorCode.MISSING_PARAMETER, "title 不能为空");
        }
        String clean = title.strip();
        if (clean.length() > MAX_TITLE_LENGTH) {
            throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "title 长度 " + clean.length() + " 超过上限 " + MAX_TITLE_LENGTH);
        }
        return clean;
    }
}
