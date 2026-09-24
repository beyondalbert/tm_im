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
    public long maxSeq(long convId) {
        Message last = mapper.selectOne(Wrappers.<Message>lambdaQuery()
                .eq(Message::getConvId, convId)
                .orderByDesc(Message::getSeq)
                .last("LIMIT 1"));
        return last == null || last.getSeq() == null ? 0L : last.getSeq();
    }

    @Override
    public long countByConv(long convId) {
        return mapper.selectCount(Wrappers.<Message>lambdaQuery().eq(Message::getConvId, convId));
    }
}
