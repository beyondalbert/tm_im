package com.tm.im.channel.support;

import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.core.message.MessageCommandPort;
import com.tm.im.core.message.MessageService;
import com.tm.im.domain.entity.Message;
import com.tm.im.domain.enums.MessageType;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link MessageCommandPort} 的内存实现，供 tm-channel 的测试使用。
 *
 * <p>它<b>不是</b>「消息服务的简化版」：这里没有幂等、没有序号源、没有扇出——
 * 那些的验证在 tm-core 的集成测试里（真实 MySQL + Redis）。
 * 本类只回答一个问题：<b>帧有没有被正确地翻译成调用</b>。
 * 具体的：
 * <ul>
 *   <li>{@code CMD_SEND} 的载荷字段有没有被原样传下去（conv_id / client_msg_id / 类型 / 内容）；</li>
 *   <li>发送者是<b>连接上鉴权得到的 actorId</b>，而不是客户端在载荷里自报的任何东西
 *       （这一条是安全边界：载荷里没有 sender 字段，但如果哪天有人加上并采信它，测试要能发现）；</li>
 *   <li>SEND_ACK 的字段与 req_id 是否正确回传；</li>
 *   <li>业务异常 <-> 错误帧的映射（40003 → 40003 而不是 50000）；</li>
 *   <li>被拒绝的请求有没有留下痕迹（不该有：seq 分配计数器不应前进）。</li>
 * </ul>
 *
 * <p><b>续传部分刻意留了一个「直接指定结果」的口子</b>（{@link #stubNextSync}）：
 * 「多轮续传怎么终止」与「非成员游标被跳过时怎么告诉客户端」是
 * {@code MessageService} 的规则（由 tm-core 的单测与集成测试验证），
 * 而在这一层要验证的是另一个问题：<b>那些结果有没有被正确翻译成帧</b>——
 * 尤其「{@code has_more=true} 时绝不能发 SYNC_END」这条，它只能靠
 * 让测试自己掌控 {@code hasMore} 才能精确地测到（否则要靠等超时来证明“没有下一帧”）。
 * 默认路径仍按真实规则从内存里取消息，以覆盖一遍正常往返。
 */
public final class InMemoryMessagePort implements MessageCommandPort {

    /** 与生产同口径：落库时间取 tm.time.zone 的墙上时间。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong messageId = new AtomicLong(730_000_000_000_000_000L);

    /** 每个会话当前的 seq，用于让重放与续传测试能观察到「号有没有被消耗」。 */
    private final Map<Long, Long> convSeq = new LinkedHashMap<>();

    /** 每个会话已落库的消息，供 {@link #sync} 默认路径读取（真实存储的极简替身）。 */
    private final Map<Long, List<Message>> byConv = new LinkedHashMap<>();

    /** 记录每一次 send 收到的命令，便于断言「帧字段被原样传下来」。 */
    public final List<MessageService.SendCommand> sent = new ArrayList<>();

    /** 记录每一次 markRead。 */
    public final List<String> reads = new ArrayList<>();

    /** 记录每一次 sync 收到的命令（游标有没有被原样传下去）。 */
    public final List<MessageService.SyncCommand> syncs = new ArrayList<>();

    /** 置为非 null 时，下一次 sync 直接返回它（见类注释）。 */
    private MessageService.SyncOutcome nextSyncOutcome;

    /** 置为非 null 时，下一次 sync 抛这个错误（用于验证错误码映射）。 */
    private ErrorCode nextSyncFailure;

    /** 置为 true 时，下一次 sync 抛一个非 TmException（验证 50000 兜底映射）。 */
    private boolean nextSyncBlowsUp;

    /** 置为非 null 时，下一次 markRead 抛这个错误（验证错误码映射）。 */
    private ErrorCode nextReadFailure;

    /** 置为非 null 时，下一次 send 抛这个错误（用于验证错误码映射）。 */
    private ErrorCode nextSendFailure;

    /** 由测试决定「下一条消息的发送者是谁不该被采信」——发送者始终是连接上的 actorId。 */
    public void failNextSendWith(ErrorCode code) {
        this.nextSendFailure = code;
    }

    /** 置为非 null 时，下一次 markRead 抛这个错误。 */
    public void failNextReadWith(ErrorCode code) {
        this.nextReadFailure = code;
    }

    /** 置为非 null 时，下一次 sync 抛这个错误（验证 5xxxx 之类的错误码不会被包装）。 */
    public void failNextSyncWith(ErrorCode code) {
        this.nextSyncFailure = code;
    }

    /** 指定下一次 sync 的结果，用于测那些「只能由服务端决定」的分支（见类注释）。 */
    public void stubNextSync(MessageService.SyncOutcome outcome) {
        this.nextSyncOutcome = outcome;
    }

    /** 下一次 sync 抛一个非 TmException（服务端缺陷，应当变成 50000 而不是静默）。 */
    public void failNextSyncUnexpectedly() {
        this.nextSyncBlowsUp = true;
    }

    /** 清空已记录的调用（用例之间互不干扰）。 */
    public void reset() {
        sent.clear();
        reads.clear();
        syncs.clear();
        nextSendFailure = null;
        nextReadFailure = null;
        nextSyncFailure = null;
        nextSyncOutcome = null;
        nextSyncBlowsUp = false;
    }

    public long currentSeq(long convId) {
        return convSeq.getOrDefault(convId, 0L);
    }

    @Override
    public MessageService.SendOutcome send(MessageService.SendCommand cmd) {
        if (nextSendFailure != null) {
            ErrorCode code = nextSendFailure;
            nextSendFailure = null;
            throw new TmException(code, "in-memory port injected failure");
        }
        sent.add(cmd);

        long next = convSeq.merge(cmd.convId(), 1L, Long::sum);

        Message message = new Message();
        message.setId(messageId.incrementAndGet());
        message.setConvId(cmd.convId());
        message.setSeq(next);
        message.setSenderId(cmd.senderId());
        message.setMsgType(cmd.msgType() == null ? MessageType.TEXT : cmd.msgType());
        message.setContent(cmd.contentJson());
        message.setReplyTo(cmd.replyTo() > 0 ? cmd.replyTo() : null);
        message.setClientMsgId(cmd.clientMsgId());
        message.setCreatedAt(LocalDateTime.now(ZONE));

        byConv.computeIfAbsent(cmd.convId(), k -> new ArrayList<>()).add(message);

        return new MessageService.SendOutcome(message, false, 0);
    }

    @Override
    public long markRead(long convId, long actorId, long lastReadSeq) {
        if (nextReadFailure != null) {
            ErrorCode code = nextReadFailure;
            nextReadFailure = null;
            throw new TmException(code, "in-memory port injected failure");
        }
        reads.add(convId + ":" + actorId + "=" + lastReadSeq);
        return lastReadSeq;
    }

    @Override
    public MessageService.SyncOutcome sync(MessageService.SyncCommand cmd) {
        if (nextSyncBlowsUp) {
            nextSyncBlowsUp = false;
            throw new IllegalStateException("in-memory port blew up");
        }
        if (nextSyncFailure != null) {
            ErrorCode code = nextSyncFailure;
            nextSyncFailure = null;
            throw new TmException(code, "in-memory port injected failure");
        }
        syncs.add(cmd);
        if (nextSyncOutcome != null) {
            MessageService.SyncOutcome stub = nextSyncOutcome;
            nextSyncOutcome = null;
            return stub;
        }

        // 默认路径：按真实规则从内存里取（多取一行判 has_more）。
        // 这里不重复真实服务的校验与跳过规则——那些是它的职责，不是本类的。
        int pageSize = cmd.limit() <= 0 ? 200 : cmd.limit();
        List<Message> collected = new ArrayList<>();
        boolean hasMore = false;
        for (MessageService.SyncCursor cursor : cmd.cursors()) {
            List<Message> page = byConv.getOrDefault(cursor.convId(), List.of()).stream()
                    .filter(m -> m.getSeq() != null && m.getSeq() > cursor.sinceSeq())
                    .sorted(Comparator.comparingLong(Message::getSeq))
                    .limit(pageSize + 1L)
                    .toList();
            if (page.size() > pageSize) {
                hasMore = true;
                page = page.subList(0, pageSize);
            }
            collected.addAll(page);
        }
        return new MessageService.SyncOutcome(List.copyOf(collected), hasMore, false,
                cmd.cursors().size(), List.of());
    }
}
