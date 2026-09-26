package com.tm.im.domain.repository;

import com.tm.im.domain.entity.Message;

import java.util.List;
import java.util.Optional;

/**
 * 消息仓储 —— 分片表 {@code message}（物理表 {@code message_0..15}）。
 *
 * <p><b>调用方必须始终携带 {@code convId}</b>。这不是风格要求：
 * 分片键就是 {@code conv_id}，不带它 ShardingSphere 只能广播到全部 16 张表再归并，
 * 单次查询放大 16 倍，高并发下会直接把数据库打满。
 * 因此这里没有任何「只按 message_id 查」的方法——那种方法在分片下必然是广播查询。
 */
public interface MessageRepository {

    /**
     * 落库并返回带最终序号的实体。
     *
     * <p><b>幂等约定</b>：当 {@code clientMsgId} 非空且已存在时，
     * 必须返回<b>已存在的那条</b>，而不是抛异常、也不是插入第二条。
     * 依据是唯一索引 {@code uk_message_idem (conv_id, sender_id, client_msg_id)}。
     * 客户端重试（网络抖动、ACK 丢失）是常态，唯一索引 + 返回既有记录
     * 才能保证「重试不产生重复消息」。
     */
    Message insert(Message message);

    Optional<Message> findBySeq(long convId, long seq);

    Optional<Message> findByIdemKey(long convId, long senderId, String clientMsgId);

    /**
     * 拉取 {@code sinceSeq} 之后的消息，按 seq 升序。
     *
     * <p>断点续传（SYNC）与历史消息都走它。因为主键是 {@code (conv_id, seq)}，
     * 这个查询是聚簇索引上的范围扫描，不需要额外排序。
     *
     * <p><b>调用方传 {@code limit + 1} 是预期的用法</b>：多取的那一行不发给客户端，
     * 只用于回答「还有没有更多」。否则调用方要先查 {@link #maxSeq} 再比大小——
     * 多一次查询，而且两次之间新插入的消息会让判断出错（无法复现的“多一轮拉取”）。
     * 因此实现不能自作主张地把 {@code limit} 改小。
     */
    List<Message> listAfterSeq(long convId, long sinceSeq, int limit);

    /** 会话内最大序号；会话为空时返回 0。用于兜底与对账。 */
    long maxSeq(long convId);

    /**
     * 会话里最新的一条消息（{@code ORDER BY seq DESC LIMIT 1}）；没有消息时返回空。
     *
     * <p>它同时回答两件事：<b>最新序号</b>（会话列表的 {@code last_seq}）与
     * <b>最后一条消息的正文</b>（列表里的预览）。
     * 分开写就是两次查询，而两次之间新插入一条消息会让两半对不上——
     * 表现为「预览显示的是 seq=7，未读数却是按 seq=8 算的」。
     *
     * <p>因为主键是 {@code (conv_id, seq)}，这是一次聚簇索引末端下降，
     * 代价与 {@code maxSeq} 同量级（后者已改为直接复用本方法）。
     */
    Optional<Message> findLatest(long convId);

    /**
     * 拉取 {@code seq < beforeSeq} 的消息，按 seq <b>倒序</b>（最新在前），用于「上滑加载历史」。
     *
     * <p>{@code beforeSeq <= 0} 表示无上界（即从最新一条开始）。
     * 之所以不用 {@code Long.MAX_VALUE} 表达「无上界」：那会在 SQL 里造出一个
     * {@code seq < 9223372036854775807} 的条件，读起来像一个有意为之的边界，
     * 而它其实是「没有条件」。
     *
     * <p>与 {@link #listAfterSeq} 一样，调用方传 {@code limit + 1} 是多取一行判 has_more
     * 的预期用法，实现不得自行改小 limit。
     */
    List<Message> listBeforeSeq(long convId, long beforeSeq, int limit);

    long countByConv(long convId);
}
