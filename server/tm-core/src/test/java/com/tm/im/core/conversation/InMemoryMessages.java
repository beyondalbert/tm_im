package com.tm.im.core.conversation;

import com.tm.im.domain.entity.Message;
import com.tm.im.domain.enums.MessageType;
import com.tm.im.domain.repository.MessageRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 内存版消息仓储（按 {@code (conv_id, seq)} 的语义实现倒序/升序两种取法）。 */
public class InMemoryMessages implements MessageRepository {

    private final List<Message> stored = new ArrayList<>();

    /** 直接往「库里」放一条消息；seq 与时间都由调用方给，方便构造「活跃时间」用例。 */
    public Message put(long convId, long seq, long senderId, String text, LocalDateTime createdAt) {
        Message message = new Message();
        message.setId(700_000_000_000_000_000L + seq);
        message.setConvId(convId);
        message.setSeq(seq);
        message.setSenderId(senderId);
        message.setMsgType(MessageType.TEXT);
        message.setContent("{\"text\":\"" + text + "\"}");
        message.setCreatedAt(createdAt);
        stored.add(message);
        return message;
    }

    @Override
    public Message insert(Message message) {
        stored.add(message);
        return message;
    }

    @Override
    public Optional<Message> findBySeq(long convId, long seq) {
        return stored.stream().filter(m -> m.getConvId() == convId && m.getSeq() == seq).findFirst();
    }

    @Override
    public Optional<Message> findByIdemKey(long convId, long senderId, String clientMsgId) {
        return stored.stream()
                .filter(m -> m.getConvId() == convId && m.getSenderId() == senderId
                        && clientMsgId != null && clientMsgId.equals(m.getClientMsgId()))
                .findFirst();
    }

    @Override
    public List<Message> listAfterSeq(long convId, long sinceSeq, int limit) {
        return stored.stream()
                .filter(m -> m.getConvId() == convId && m.getSeq() > sinceSeq)
                .sorted(Comparator.comparingLong(Message::getSeq))
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public Optional<Message> findLatest(long convId) {
        return stored.stream()
                .filter(m -> m.getConvId() == convId)
                .max(Comparator.comparingLong(Message::getSeq));
    }

    @Override
    public List<Message> listBeforeSeq(long convId, long beforeSeq, int limit) {
        return stored.stream()
                .filter(m -> m.getConvId() == convId && (beforeSeq <= 0 || m.getSeq() < beforeSeq))
                .sorted(Comparator.comparingLong(Message::getSeq).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public long maxSeq(long convId) {
        return findLatest(convId).map(Message::getSeq).orElse(0L);
    }

    @Override
    public long countByConv(long convId) {
        return stored.stream().filter(m -> m.getConvId() == convId).count();
    }
}
