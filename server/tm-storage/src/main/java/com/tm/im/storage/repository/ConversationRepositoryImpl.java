package com.tm.im.storage.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tm.im.domain.entity.Conversation;
import com.tm.im.domain.entity.ConversationMember;
import com.tm.im.domain.enums.MemberRole;
import com.tm.im.domain.repository.ConversationRepository;
import com.tm.im.storage.mapper.ConversationMapper;
import com.tm.im.storage.mapper.ConversationMemberMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ConversationRepositoryImpl implements ConversationRepository {

    private final ConversationMapper conversationMapper;
    private final ConversationMemberMapper memberMapper;

    public ConversationRepositoryImpl(ConversationMapper conversationMapper,
                                      ConversationMemberMapper memberMapper) {
        this.conversationMapper = conversationMapper;
        this.memberMapper = memberMapper;
    }

    @Override
    public Optional<Conversation> findById(long convId) {
        return Optional.ofNullable(conversationMapper.selectById(convId));
    }

    @Override
    public Optional<Conversation> findDirectByPairKey(String pairKey) {
        if (pairKey == null || pairKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(conversationMapper.selectOne(
                Wrappers.<Conversation>lambdaQuery()
                        .eq(Conversation::getPairKey, pairKey)
                        .last("LIMIT 1")));
    }

    @Override
    @Transactional
    public Conversation insert(Conversation conversation) {
        if (conversation.getCreatedAt() == null) {
            conversation.setCreatedAt(LocalDateTime.now());
        }
        conversationMapper.insert(conversation);
        return conversation;
    }

    @Override
    @Transactional
    public boolean addMember(long convId, long actorId, MemberRole role) {
        ConversationMember member = new ConversationMember();
        member.setConvId(convId);
        member.setActorId(actorId);
        member.setRole(role);
        member.setLastReadSeq(0L);
        member.setMuted(false);
        member.setJoinedAt(LocalDateTime.now());
        try {
            memberMapper.insert(member);
            return true;
        } catch (DuplicateKeyException e) {
            // 已存在 → 幂等返回 false，让调用方可以安全重试「进群」操作
            return false;
        }
    }

    @Override
    public void updateLastReadSeq(long convId, long actorId, long lastReadSeq) {
        // 已读游标只允许前进，不允许被迟到的请求回退。
        // 若不判断，客户端乱序到达的两次上报会让未读数凭空变大。
        memberMapper.update(null, Wrappers.<ConversationMember>lambdaUpdate()
                .eq(ConversationMember::getConvId, convId)
                .eq(ConversationMember::getActorId, actorId)
                .lt(ConversationMember::getLastReadSeq, lastReadSeq)
                .set(ConversationMember::getLastReadSeq, lastReadSeq));
    }

    @Override
    public Optional<ConversationMember> findMember(long convId, long actorId) {
        return Optional.ofNullable(memberMapper.selectOne(Wrappers.<ConversationMember>lambdaQuery()
                .eq(ConversationMember::getConvId, convId)
                .eq(ConversationMember::getActorId, actorId)
                .last("LIMIT 1")));
    }

    @Override
    public boolean isMember(long convId, long actorId) {
        return memberMapper.exists(Wrappers.<ConversationMember>lambdaQuery()
                .eq(ConversationMember::getConvId, convId)
                .eq(ConversationMember::getActorId, actorId));
    }

    @Override
    public List<Conversation> listByActor(long actorId, int limit) {
        List<ConversationMember> memberships = memberMapper.selectList(
                Wrappers.<ConversationMember>lambdaQuery()
                        .eq(ConversationMember::getActorId, actorId)
                        .orderByDesc(ConversationMember::getJoinedAt)
                        .last("LIMIT " + Math.max(1, limit)));
        if (memberships.isEmpty()) {
            return List.of();
        }
        List<Long> convIds = memberships.stream().map(ConversationMember::getConvId).toList();
        return conversationMapper.selectBatchIds(convIds);
    }

    @Override
    public List<Long> listMemberIds(long convId, int limit) {
        return memberMapper.selectList(Wrappers.<ConversationMember>lambdaQuery()
                        .eq(ConversationMember::getConvId, convId)
                        .last("LIMIT " + Math.max(1, limit)))
                .stream().map(ConversationMember::getActorId).toList();
    }

    /**
     * 从数据库推进会话序号 —— <b>仅作为 Redis 不可用时的兜底</b>。
     *
     * <p>正常路径是 Redis {@code INCR tm:seq:{convId}}：一趟内存往返、
     * 天然原子、还能顺带做过期清理。
     *
     * <p>这里的实现依赖 {@code UPDATE ... SET seq_counter = seq_counter + 1} 拿到的行锁：
     * 并发的调用会在该行上串行化，因此不会取到重复序号。
     * 代价是每次分配都要写一次磁盘页，吞吐远低于 Redis——所以它只是兜底，
     * 一旦被高频调用就说明 Redis 出问题了，应当告警而不是默默扛着。
     */
    @Override
    @Transactional
    public long nextSeqFromDb(long convId) {
        conversationMapper.update(null, Wrappers.<Conversation>lambdaUpdate()
                .eq(Conversation::getId, convId)
                .setSql("seq_counter = seq_counter + 1"));
        Conversation c = conversationMapper.selectById(convId);
        if (c == null) {
            throw new IllegalStateException("会话不存在，无法分配序号: convId=" + convId);
        }
        return c.getSeqCounter() == null ? 0L : c.getSeqCounter();
    }
}
