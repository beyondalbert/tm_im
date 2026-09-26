package com.tm.im.storage.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tm.im.domain.entity.Message;
import com.tm.im.domain.repository.MessageRepository;
import com.tm.im.storage.mapper.MessageMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 消息仓储实现。
 *
 * <p><b>每个方法第一个参数都是 convId，没有例外。</b>
 * 分片键就是 comm_id，所有 SQL 都必须带上它才能让 ShardingSphere 精确路由到单张表；
 * 漏掉它会退化成 16 张表的广播 + 内存归并，单次查询放大 16 倍。
 */
@Repository
public class MessageRepositoryImpl implements MessageRepository {

    private final MessageMapper mapper;

    public MessageRepositoryImpl(MessageMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 幂等落库。
     *
     * <p>实现顺序刻意是「先查 → 再插 → 撞唯一索引则回查」，而不是「先插 → 撞了再查」：
     * 绝大多数重试都会命中第一步（客户端重试通常紧接着上一次失败），
     * 这样不会白白消耗一次自增和一次回滚。真正并发的那一小部分由第二步的唯一索引兜底。
     *
     * <p><b>为什么不靠 SELECT 判重就完事</b>：两个请求可能同时查不到、同时插入，
     * 唯一索引 {@code uk_message_idem (conv_id, sender_id, client_msg_id)} 才是真正的防线。
     * 索引的三个列都包含分片列 conv_id，所以约束在分片内有效——
     * 这正是 DDL 里坚持把 conv_id 放进幂等唯一索引的原因（DESIGN §8.5）。
     */
    @Override
    @Transactional
    public Message insert(Message message) {
        if (message.getClientMsgId() != null && !message.getClientMsgId().isBlank()) {
            Optional<Message> existing = findByIdemKey(
                    message.getConvId(), message.getSenderId(), message.getClientMsgId());
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        try {
            mapper.insert(message);
            return message;
        } catch (DuplicateKeyException e) {
            // 并发插入：另一方已写入，回查并返回既有的那条，保持幂等语义
            if (message.getClientMsgId() != null && !message.getClientMsgId().isBlank()) {
                return findByIdemKey(message.getConvId(), message.getSenderId(), message.getClientMsgId())
                        .orElseThrow(() -> e);
            }
            throw e;
        }
    }

    @Override
    public Optional<Message> findBySeq(long convId, long seq) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<Message>lambdaQuery()
                .eq(Message::getConvId, convId)
                .eq(Message::getSeq, seq)
                .last("LIMIT 1")));
    }

    @Override
    public Optional<Message> findByIdemKey(long convId, long senderId, String clientMsgId) {
        if (clientMsgId == null || clientMsgId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(Wrappers.<Message>lambdaQuery()
                .eq(Message::getConvId, convId)
                .eq(Message::getSenderId, senderId)
                .eq(Message::getClientMsgId, clientMsgId)
                .last("LIMIT 1")));
    }

    @Override
    public List<Message> listAfterSeq(long convId, long sinceSeq, int limit) {
        return mapper.selectList(Wrappers.<Message>lambdaQuery()
                .eq(Message::getConvId, convId)
                .gt(Message::getSeq, sinceSeq)
                .orderByAsc(Message::getSeq)
                .last("LIMIT " + Math.max(1, limit)));
    }

    @Override
    public List<Message> listBeforeSeq(long convId, long beforeSeq, int limit) {
        var query = Wrappers.<Message>lambdaQuery().eq(Message::getConvId, convId);
        // 条件必须在 .last() 之前追加：MyBatis-Plus 按调用顺序拼 SQL 片段，
        // .last("LIMIT n") 之后再 .lt(...) 会拼出 "... LIMIT 10 AND seq < 5"，
        // 而这条 SQL 在 MySQL 里是语法错，在别的库里可能是「条件被忽略」。
        if (beforeSeq > 0) {
            query.lt(Message::getSeq, beforeSeq);
        }
        return mapper.selectList(query
                .orderByDesc(Message::getSeq)
                .last("LIMIT " + Math.max(1, limit)));
    }

    /**
     * 最新一条。
     *
     * <p>与 {@link #maxSeq} 是同一次查询（后者改为直接复用本方法）：
     * 主键就是 {@code (conv_id, seq)}，所以「最大的 seq」与「那一行」
     * 本来就一起拿到，分成两次查询只会多一趟往返并多一个不一致窗口。
     */
    @Override
    public Optional<Message> findLatest(long convId) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<Message>lambdaQuery()
                .eq(Message::getConvId, convId)
                .orderByDesc(Message::getSeq)
                .last("LIMIT 1")));
    }

    @Override
    public long maxSeq(long convId) {
        return findLatest(convId).map(Message::getSeq).orElse(0L);
    }

    @Override
    public long countByConv(long convId) {
        return mapper.selectCount(Wrappers.<Message>lambdaQuery().eq(Message::getConvId, convId));
    }
}
