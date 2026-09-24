package com.tm.im.core.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.common.id.IdGenerator;
import com.tm.im.domain.entity.Conversation;
import com.tm.im.domain.entity.Message;
import com.tm.im.domain.enums.ConvType;
import com.tm.im.domain.enums.FriendshipStatus;
import com.tm.im.domain.enums.MessageType;
import com.tm.im.domain.policy.MessageSendPolicy;
import com.tm.im.domain.repository.ConversationRepository;
import com.tm.im.domain.repository.FriendshipRepository;
import com.tm.im.domain.repository.MessageRepository;
import com.tm.im.core.channel.MessagePushPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 消息写入 —— DESIGN §10.1 的实现，也是 REST 与长连接<b>共用</b>的那一条路径。
 *
 * <pre>
 *   1. 参数与内容校验（msg_type / content 结构 / 长度）
 *   2. 权限：成员身份 +（单聊）好友关系
 *   3. 幂等短路：同一 (convId, senderId, clientMsgId) 已存在 → 直接回执，<b>不取号</b>
 *   4. 取 seq：ConversationRepository.nextSeq（Redis INCR，异常则落库兜底）
 *   5. 落库：撞 (conv_id, seq) 主键 → 序号源自愈 + 重试
 *   6. 扇出：按成员数决定写扩散还是读扩散
 * </pre>
 *
 * <p><b>为什么幂等短路必须在取号之前</b>：seq 是会被写进主键的离散量，取号即消耗。
 * 客户端重试（ACK 丢失、网络抖动）是常态；先取号再判重的话，每次重试都会在会话里
 * 留下一个永久空洞——「为什么少了几个号」将永远无法回答。
 *
 * <p><b>幂等重放不做扇出</b>：那条消息在第一次成功时已经推过。重复推送会让对方界面
 * 出现两条一样的消息（客户端按 seq 去重是后手，服务端没有理由把去重责任推给客户端）。
 * 代价是一次「落库成功、扇出前崩溃」之后对方只能靠 SYNC 补齐——这与
 * DESIGN §10.1「落库即可 ACK、扇出可以丢」的取舍一致。
 *
 * <p><b>本类除了数据库之外不做等待</b>：扇出走的是本节点内存注册表
 * （{@link MessagePushPort} 的本地实现）。跨节点投递要经 Redis Pub/Sub，
 * 那时才必须挪到独立线程池；现在挪只会引入一个没有收益的异步边界。
 *
 * <p><b>本类同时实现断点续传的<b>读</b>路径</b>（{@link #sync}，DESIGN §10.2）：
 * 它读的就是写路径落下的同一张表、同一个 {@code seq} 序列。两者放在一个类里，
 * 是因为它们共用同一条不变量——<b>{@code seq} 在会话内严格递增且允许有空洞</b>；
 * 写入侧为此不自愈不重排（“取号即消耗”），读取侧因此只能表达
 * “凡 seq 大于游标的都已返回”，而不能表达“序列连续”。两边分开写就会各自漂。
 */
@Service
public class MessageService implements MessageCommandPort {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    /**
     * 序号撞主键后的重试次数。
     *
     * <p>什么情况下会撞：Redis 被清空 / 故障切换到空实例后 {@code tm:seq:{convId}}
     * 从 1 重新开始，而库里已有 seq=1..N。自愈一次即可把基线抬到 N 之上，
     * 所以第 2 次尝试必然成功；3 次是留给自愈期间「别的节点又把基线抬高」的余地。
     *
     * <p>超过次数必须报错而不是继续重试：无限重试会把一个错误变成一个死循环。
     */
    private static final int MAX_SEQ_RETRIES = 3;

    private static final ObjectMapper JSON = com.tm.im.common.json.Json.mapper();

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final FriendshipRepository friendships;
    private final MessagePushPort push;
    private final IdGenerator idGenerator;
    private final MessageProperties properties;
    private final ZoneId databaseZone;

    public MessageService(ConversationRepository conversations,
                          MessageRepository messages,
                          FriendshipRepository friendships,
                          MessagePushPort push,
                          IdGenerator idGenerator,
                          MessageProperties properties,
                          ZoneId databaseZone) {
        this.conversations = conversations;
        this.messages = messages;
        this.friendships = friendships;
        this.push = push;
        this.idGenerator = idGenerator;
        this.properties = properties;
        this.databaseZone = databaseZone;
    }

    /** 发送请求。{@code clientMsgId} 为空表示放弃幂等（协议允许，但不推荐）。 */
    public record SendCommand(long convId,
                              long senderId,
                              String clientMsgId,
                              MessageType msgType,
                              String contentJson,
                              long replyTo) {
    }

    /**
     * 发送结果。
     *
     * @param message  最终落库的那条（{@code seq} 是服务端分配的权威值）
     * @param replayed true 表示这是幂等重放，没有产生新消息、也没有重复推送
     * @param pushed   本次实际写入的连接数（0 = 对方离线或不在本节点）
     */
    public record SendOutcome(Message message, boolean replayed, int pushed) {
    }

    @Override
    public SendOutcome send(SendCommand cmd) {
        validate(cmd);

        Conversation conv = conversations.findById(cmd.convId())
                .orElseThrow(() -> new TmException(ErrorCode.CONVERSATION_NOT_FOUND,
                        "convId=" + cmd.convId()));

        boolean member = conversations.isMember(cmd.convId(), cmd.senderId());
        checkSendPermission(conv, cmd.senderId(), member, cmd.msgType());

        // 幂等短路（先于取号，见类注释）
        if (isIdempotencyKeyUsable(cmd.clientMsgId())) {
            Optional<Message> existing = messages.findByIdemKey(
                    cmd.convId(), cmd.senderId(), cmd.clientMsgId());
            if (existing.isPresent()) {
                log.debug("幂等重放：未取号、未落库 convId={} seq={} clientMsgId={}",
                        cmd.convId(), existing.get().getSeq(), cmd.clientMsgId());
                return new SendOutcome(existing.get(), true, 0);
            }
        }

        Inserted inserted = insertWithSeqSelfHeal(cmd);
        int pushed = inserted.replayed()
                ? 0
                : fanout(cmd.convId(), cmd.senderId(), inserted.message());
        return new SendOutcome(inserted.message(), inserted.replayed(), pushed);
    }

    /** 已读上报。幂等：游标只前进不后退（由仓储的条件更新保证）。 */
    @Override
    public long markRead(long convId, long actorId, long lastReadSeq) {
        if (convId <= 0 || actorId <= 0) {
            throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "convId=" + convId + ", actorId=" + actorId);
        }
        if (lastReadSeq < 0) {
            throw new TmException(ErrorCode.INVALID_PARAMETER, "last_read_seq=" + lastReadSeq);
        }
        if (!conversations.isMember(convId, actorId)) {
            throw new TmException(ErrorCode.NOT_A_MEMBER, "convId=" + convId + ", actorId=" + actorId);
        }
        // 游标不能越过会话当前最大 seq。客户端多报一次并不会「多读」什么，
        // 但会把尚未产生的消息标记为已读，此后再也补不回来——SYNC 的补拉
        // 完全依赖这个游标（04-realtime.md §6），所以必须在这里拦。
        long max = messages.maxSeq(convId);
        if (lastReadSeq > max) {
            throw new TmException(ErrorCode.INVALID_CURSOR,
                    "last_read_seq=" + lastReadSeq + " 超过会话当前最大 seq=" + max);
        }
        conversations.updateLastReadSeq(convId, actorId, lastReadSeq);
        return lastReadSeq;
    }

    // ------------------------------------------------------------------ 续传读取

    /** 续传请求里的一个会话游标：只要该会话 {@code seq > sinceSeq} 的消息。 */
    public record SyncCursor(long convId, long sinceSeq) {
    }

    /**
     * 续传请求（04-realtime.md §6.2）。一次可以带多个会话的游标。
     *
     * @param actorId 已鉴权的身份，<b>不是</b>客户端自报的字段——每个游标都要拿它做成员校验
     * @param limit   每个会话最多返回多少条；{@code <= 0} 表示用服务端默认值
     */
    public record SyncCommand(long actorId, List<SyncCursor> cursors, int limit) {
    }

    /**
     * 续传结果。
     *
     * @param messages     本次要发给客户端的消息，按「请求里的游标顺序 → 会话内 seq 升序」排列
     * @param hasMore      本轮还没补齐：客户端把游标推到本帧最后一条的 seq 后再发一次
     * @param truncated    服务端补不齐（见 {@link #sync} 的说明；当前恒为 false）
     * @param convsSynced  本轮真正服务了的会话数（不含被跳过的）
     * @param skippedConvs 被跳过的会话（非成员 / 会话不存在）：一条消息也不返回，但也不让整轮失败
     */
    public record SyncOutcome(List<Message> messages,
                              boolean hasMore,
                              boolean truncated,
                              int convsSynced,
                              List<Long> skippedConvs) {
    }

    /**
     * 断点续传拉取（{@code CMD_SYNC}，DESIGN §10.2）。
     *
     * <p><b>只按 {@code seq > since_seq} 查，与断线时长、与消息产生时间完全无关</b>
     * （04-realtime.md §6.3）。这是「一条不漏」的全部依据：任何基于时间窗的补拉
     * 都会在冷会话上漏消息，而冷会话恰恰是断线最久、最需要补的那个。
     *
     * <p><b>{@code has_more} 是精确值，不是估计值</b>：每个会话多取一行
     * （{@code limit + 1}），多出来的那一行只用于回答「还有没有」。
     * 换一种写法（查 {@code maxSeq} 再比大小）会多一次查询，
     * 而且两次查询之间刚好插入一条新消息时会把 {@code has_more} 判成 true——
     * 那是一个纯粹由并发引入的、无法复现的「多一轮拉取」。
     *
     * <p><b>它不是「补齐了才返回」</b>：一帧最多带
     * {@code 游标数 × 每个游标的 limit} 条消息，所以本轮没补完时
     * {@code hasMore=true}，由客户端推进游标后再请求一次。
     * 由此得到一条可被测试钉住的不变量：<b>{@code hasMore=true} 的响应里不可能一条消息都没有</b>
     * （{@code limit >= 1} 才会走到「多出来一行」，所以客户端总能推进游标，不会死循环）。
     *
     * <p><b>非成员 / 不存在的会话游标被跳过，而不是让整轮失败</b>：
     * 退群之后客户端的本地会话列表不会当场消失，它会一直带着那个游标重连。
     * 若为此回 40303，该用户从此<b>补不了任何会话</b>的消息（包括那些与他无关的失败），
     * 而修它的办法（清理本地游标）恰好又要靠 SYNC 才能发现。
     * 跳过的游标会出现在 {@link SyncOutcome#skippedConvs()} 里并被记 WARN，
     * 由 {@code CMD_SYNC_END} 的 {@code message} 字段告诉客户端——不是静默。
     *
     * <p><b>{@code truncated} 目前恒为 false，且这是刻意的</b>：
     * 它的含义是「你要的区间里有一部分服务端已经给不出来了」。
     * 消息表允许 {@code seq} 有空洞（取号即消耗，见
     * {@link #insertWithSeqSelfHeal}），所以「某一行不存在」
     * <b>不能</b>被解释成「已经被删了」——那是假警报，而假警报会让客户端去 REST 重拉一遍
     * 拿到同样的东西。真正能回答这个问题的只有归档/清理任务的<b>水位</b>
     * （「本会话 {@code seq <= N} 的消息已被归档」），它还没实现，
     * 因此现在没有任何依据把 {@code truncated} 置为 true。
     * 归档任务上线时应当补一个水位并在读到它之前置位，而不是在这里猜。
     */
    @Override
    public SyncOutcome sync(SyncCommand cmd) {
        validateSync(cmd);

        // 每会话的配额。客户端传 0 表示「用服务端默认值」（proto 里 limit 的语义）。
        int pageSize = properties.clampPullSize(cmd.limit());
        List<Message> collected = new ArrayList<>();
        List<Long> skipped = new ArrayList<>();
        boolean hasMore = false;

        // 顺序执行而不是并行：业务线程池的并发度就是这里的背压。
        // 并行化会把「一个客户端的一帧」变成 50 个并发查询，那是把背压从连接级
        // 搬到数据库连接池上——池子满之后，症状是全体用户的请求一起变慢。
        for (SyncCursor cursor : cmd.cursors()) {
            if (!conversations.isMember(cursor.convId(), cmd.actorId())) {
                skipped.add(cursor.convId());
                log.warn("SYNC 跳过不可访问的会话 actorId={} convId={}（非成员或会话不存在）",
                        cmd.actorId(), cursor.convId());
                continue;
            }
            List<Message> page = messages.listAfterSeq(cursor.convId(), cursor.sinceSeq(), pageSize + 1);
            if (page.size() > pageSize) {
                hasMore = true;
                page = page.subList(0, pageSize);
            }
            collected.addAll(page);
        }

        int convsSynced = cmd.cursors().size() - skipped.size();
        log.debug("SYNC actorId={} 游标={} 服务会话={} 返回={} 条 hasMore={} 跳过={}",
                cmd.actorId(), cmd.cursors().size(), convsSynced, collected.size(), hasMore, skipped);
        return new SyncOutcome(List.copyOf(collected), hasMore, false, convsSynced, List.copyOf(skipped));
    }

    // ------------------------------------------------------------------ 校验

    /**
     * 续传请求的准入。
     *
     * <p>三种拒绝都不能含混：游标数为 0 是「客户端没什么要补的，却还是发了请求」
     * （40001），负游标是「游标本身坏了」（40010，与 {@link #markRead} 同一个码），
     * 同一个 conv_id 出现两次则是「本轮该按哪个游标算」根本没有定义——
     * 这种歧义不能由服务端静默挑一个（挑高的会跳过中间的消息，挑低的会重复下发）。
     */
    private void validateSync(SyncCommand cmd) {
        if (cmd.actorId() <= 0) {
            throw new TmException(ErrorCode.INVALID_PARAMETER, "actorId=" + cmd.actorId());
        }
        List<SyncCursor> cursors = cmd.cursors();
        if (cursors == null || cursors.isEmpty()) {
            throw new TmException(ErrorCode.MISSING_PARAMETER, "cursors 不能为空");
        }
        int max = properties.getMaxCursorsPerSync();
        if (cursors.size() > max) {
            // 说清「请分批」，而不是只说「参数非法」：客户端有能力自己修好这件事，
            // 但前提是它知道上限在哪、以及这不是一个需要重试的错误。
            throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "cursors 数量 " + cursors.size() + " 超过单次上限 " + max + "，请分批发送");
        }
        Set<Long> seen = new HashSet<>();
        for (SyncCursor cursor : cursors) {
            if (cursor.convId() <= 0) {
                throw new TmException(ErrorCode.INVALID_PARAMETER, "conv_id 必须为正整数: " + cursor.convId());
            }
            if (cursor.sinceSeq() < 0) {
                throw new TmException(ErrorCode.INVALID_CURSOR, "since_seq 不能为负: " + cursor.sinceSeq());
            }
            if (!seen.add(cursor.convId())) {
                throw new TmException(ErrorCode.INVALID_PARAMETER,
                        "conv_id " + cursor.convId() + " 在一次请求里出现了多次");
            }
        }
    }

    private void validate(SendCommand cmd) {
        if (cmd.convId() <= 0) {
            throw new TmException(ErrorCode.MISSING_PARAMETER, "conv_id 必须为正整数");
        }
        if (cmd.senderId() <= 0) {
            throw new TmException(ErrorCode.MISSING_PARAMETER, "sender_id 必须为正整数");
        }
        if (cmd.msgType() == null) {
            throw new TmException(ErrorCode.INVALID_MSG_TYPE, "msg_type 缺失");
        }
        if (cmd.contentJson() == null || cmd.contentJson().isBlank()) {
            throw new TmException(ErrorCode.MISSING_PARAMETER, "content 不能为空");
        }

        JsonNode content;
        try {
            content = JSON.readTree(cmd.contentJson());
        } catch (Exception e) {
            // 不是合法 JSON 与「字段缺失」是两类问题，所以这里是 40002 而不是 40009
            throw new TmException(ErrorCode.INVALID_PARAMETER, "content 不是合法 JSON");
        }
        if (content == null || !content.isObject()) {
            throw new TmException(ErrorCode.INVALID_PARAMETER, "content 必须是 JSON 对象");
        }

        switch (cmd.msgType()) {
            case TEXT -> validateText(content);
            case IMAGE -> validateImage(content);
            case SYSTEM -> validateSystem(content);
        }
    }

    private void validateText(JsonNode content) {
        JsonNode text = content.get("text");
        if (text == null || !text.isTextual()) {
            throw new TmException(ErrorCode.CONTENT_TYPE_MISMATCH,
                    "TEXT 消息的 content 必须含字符串字段 text（03-rest-api.md §4.5）");
        }
        if (text.asText().length() > properties.getMaxTextLength()) {
            // 报错而不是截断：截断会让发送方以为发全了、接收方看到的却少一截，
            // 而两边都无从发现（07-errors-limits.md §2.1：文字 ≤ 5000 字符）
            throw new TmException(ErrorCode.CONTENT_TOO_LONG,
                    "text 长度 " + text.asText().length() + " 超过上限 " + properties.getMaxTextLength());
        }
    }

    private void validateImage(JsonNode content) {
        JsonNode mediaId = content.get("media_id");
        if (mediaId == null || !mediaId.isNumber() || mediaId.asLong() <= 0) {
            throw new TmException(ErrorCode.CONTENT_TYPE_MISMATCH,
                    "IMAGE 消息的 content 必须含正整数字段 media_id");
        }
        // 刻意不在这里查媒体表：图片是否存在由上传接口保证，
        // 而这条路径是全区最热的写路径，不该被另一张表拖慢。
    }

    private void validateSystem(JsonNode content) {
        JsonNode action = content.get("action");
        if (action == null || !action.isTextual()) {
            throw new TmException(ErrorCode.CONTENT_TYPE_MISMATCH,
                    "SYSTEM 消息的 content 必须含字符串字段 action");
        }
    }

    // ------------------------------------------------------------------ 权限

    /**
     * 发消息准入。
     *
     * <p>群聊与单聊的差别交给 {@link MessageSendPolicy}（唯一实现处，已穷举全部组合），
     * 这里只负责把「好友状态」查出来——它是 policy 的输入，不是 policy 的职责。
     */
    private void checkSendPermission(Conversation conv, long senderId, boolean member,
                                     MessageType msgType) {
        if (!member) {
            // 成员身份必须<b>最先</b>判，而不是交给 policy 顺便判。
            // 原因：下面要算「单聊里对方是谁」，而「单聊正好两名成员」这个前提
            // 对非成员发送者不成立——一个与会员完全无关的人来发消息，
            // 会把「其余成员」算成两个人，从而报出 50000（会话成员数异常）。
            // 这是真发生过的：本类的单测把「非成员」与「好友」两个维度分开测时它才暴露。
            throw new TmException(ErrorCode.NOT_A_MEMBER,
                    "convId=" + conv.getId() + ", actorId=" + senderId);
        }
        if (msgType == MessageType.SYSTEM) {
            // DESIGN §10.1：系统消息豁免好友检查（它承载邀请/建群通知，
            // 本身就没有「双方好友」这个前提），但仍是会话成员。
            return;
        }
        ConvType convType = conv.getConvType();
        if (convType == null) {
            throw new TmException(ErrorCode.INTERNAL_ERROR,
                    "会话类型缺失 convId=" + conv.getId() + "（数据可能已损坏）");
        }
        // 群聊不查好友关系：那意味着每条群消息对全体成员各做一次好友查询，
        // 500 人群就是 500 次查询（MessageSendPolicy 类注释里有完整理由）。
        FriendshipStatus friendship = convType == ConvType.DIRECT
                ? friendshipWithPeer(conv.getId(), senderId)
                : null;
        MessageSendPolicy.check(convType, true, friendship);
    }

    /** 单聊里「对方」的好友状态；查不到（无任何关系）返回 null，policy 判为 40003。 */
    private FriendshipStatus friendshipWithPeer(long convId, long senderId) {
        // 单聊固定两人，这里多取一个是为了发现「单聊里有第三个人」这种数据异常：
        // 那种情况下「谁是对方」根本没有定义，静默挑一个比报错更危险。
        List<Long> members = conversations.listMemberIds(convId, 3);
        List<Long> peers = members.stream().filter(id -> id != senderId).toList();
        if (peers.size() > 1) {
            log.error("单聊会话里不止两名成员 convId={} members={} —— 数据异常", convId, members);
            throw new TmException(ErrorCode.INTERNAL_ERROR, "DIRECT 会话成员数异常: " + members.size());
        }
        if (peers.isEmpty()) {
            // 会话里只有发送者自己：谈不上好友关系，按「非好友」拒绝
            return null;
        }
        return friendships.find(senderId, peers.get(0))
                .map(f -> f.getStatus())
                .orElse(null);
    }

    // ------------------------------------------------------------------ 落库

    private record Inserted(Message message, boolean replayed) {
    }

    /**
     * 取号 + 落库，撞主键时自愈重试。
     *
     * @return 落库结果；{@code replayed=true} 表示唯一索引拦下了一次并发重复提交，
     *         返回的是对方写入的那条
     */
    private Inserted insertWithSeqSelfHeal(SendCommand cmd) {
        Message message = new Message();
        long generatedId = idGenerator.nextId();
        message.setId(generatedId);
        message.setConvId(cmd.convId());
        message.setSenderId(cmd.senderId());
        message.setMsgType(cmd.msgType());
        message.setContent(cmd.contentJson());
        message.setReplyTo(cmd.replyTo() > 0 ? cmd.replyTo() : null);
        message.setClientMsgId(isIdempotencyKeyUsable(cmd.clientMsgId()) ? cmd.clientMsgId() : null);
        // 库里是 DATETIME(3)，它代表哪个时区由 tm.time.zone 定义（必须与 sharding.yaml
        // 的 serverTimezone 一致）。所以显式用 databaseZone 取墙上时间，
        // 而不是 LocalDateTime.now() 去依赖 JVM 默认时区——那会在容器里差 8 小时。
        message.setCreatedAt(LocalDateTime.now(databaseZone));

        for (int attempt = 1; attempt <= MAX_SEQ_RETRIES; attempt++) {
            message.setSeq(conversations.nextSeq(cmd.convId()));
            try {
                Message saved = messages.insert(message);
                // 仓储的幂等约定是「幂等键已存在则返回已存在的那条」。
                // 因此返回的对象 id 与本次生成的 id 不同，就说明唯一索引拦下了并发重复提交。
                boolean replayed = saved.getId() == null || saved.getId() != generatedId;
                if (replayed) {
                    log.info("并发重复提交被幂等键拦下 convId={} clientMsgId={} seq={}",
                            cmd.convId(), cmd.clientMsgId(), saved.getSeq());
                }
                return new Inserted(saved, replayed);
            } catch (DuplicateKeyException e) {
                // 判据刻意不是「解析异常文本里的索引名」（改索引名即失效）。表上只有两个
                // 唯一索引：uk_message_idem 与 PRIMARY (conv_id, seq)。按幂等键回查命中
                // 就是前者，没命中就只可能是序号撞了。
                Optional<Message> concurrent = isIdempotencyKeyUsable(cmd.clientMsgId())
                        ? messages.findByIdemKey(cmd.convId(), cmd.senderId(), cmd.clientMsgId())
                        : Optional.empty();
                if (concurrent.isPresent()) {
                    return new Inserted(concurrent.get(), true);
                }
                long floor = messages.maxSeq(cmd.convId());
                log.warn("序号撞主键，触发序号源自愈 convId={} attempt={} 库内最大 seq={} 本次 seq={}",
                        cmd.convId(), attempt, floor, message.getSeq());
                conversations.raiseSeqFloor(cmd.convId(), floor);
            }
        }
        throw new TmException(ErrorCode.DATABASE_UNAVAILABLE,
                "序号源持续错乱，自愈 " + MAX_SEQ_RETRIES + " 次后仍撞主键 convId=" + cmd.convId());
    }

    // ------------------------------------------------------------------ 扇出

    /**
     * 扇出（DESIGN §10.3）。
     *
     * <p>大群只落库、不逐成员推送：10 万人群发一条消息会触发 10 万次写，
     * 那是能把整个系统打趴的量级。转读扩散后客户端按 {@code last_seq} 自己拉。
     *
     * <p>多取一名成员（{@code threshold + 1}）是为了「不必知道确切人数就能判断是否超限」：
     * {@code count(*)} 在大群上是全索引扫描，而这个判断发生在每条消息的写路径上。
     */
    private int fanout(long convId, long senderId, Message message) {
        int threshold = properties.getWriteFanoutThreshold();
        List<Long> members = conversations.listMemberIds(convId, threshold + 1);
        if (members.size() > threshold) {
            log.info("成员数超过写扩散阈值({})，转读扩散 convId={} seq={}",
                    threshold, convId, message.getSeq());
            return 0;
        }
        int delivered = 0;
        for (long member : members) {
            if (member == senderId) {
                // 不回推给发送者自己的连接：它已经收到携带权威 seq 的 SEND_ACK，
                // 再推一条 PUSH 会让同一个界面渲染出两条相同的消息。
                continue;
            }
            delivered += push.pushToActor(member, message);
        }
        return delivered;
    }

    /** 幂等键是否可用。REST 与长连接两条链路共用同一判断，避免两处各写一份。 */
    public static boolean isIdempotencyKeyUsable(String clientMsgId) {
        return clientMsgId != null && !clientMsgId.isBlank();
    }
}
