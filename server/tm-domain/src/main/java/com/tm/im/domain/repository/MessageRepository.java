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
     */
    List<Message> listAfterSeq(long convId, long sinceSeq, int limit);

    /** 会话内最大序号；会话为空时返回 0。用于兜底与对账。 */
    long maxSeq(long convId);

    long countByConv(long convId);
}
