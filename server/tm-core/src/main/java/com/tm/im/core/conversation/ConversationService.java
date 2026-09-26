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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
