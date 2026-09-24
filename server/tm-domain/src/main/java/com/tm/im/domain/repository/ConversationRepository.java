package com.tm.im.domain.repository;

import com.tm.im.domain.entity.Conversation;
import com.tm.im.domain.entity.ConversationMember;

import java.util.List;
import java.util.Optional;

/**
 * 会话仓储。单聊与群聊共用同一套结构（{@code conv_type} 区分），
 * 这样消息表不需要为两种会话分别设计。
 */
public interface ConversationRepository {

    Optional<Conversation> findById(long convId);

    /**
     * 按去重键查找单聊会话。
     *
     * <p>去重键格式为 {@code minActorId_maxActorId}（见 {@code PairKeys}）。
     * 有了它，A 发起与 B 发起会命中同一个会话，
     * 不需要依赖「先查再插」的应用层竞态控制——数据库的唯一索引会兜底。
     */
    Optional<Conversation> findDirectByPairKey(String pairKey);

    Conversation insert(Conversation conversation);

    /** 新增成员。已存在时返回 false（幂等，可安全重试）。 */
    boolean addMember(long convId, long actorId, com.tm.im.domain.enums.MemberRole role);

    /** 幂等：重复调用与调用一次等价。 */
    void updateLastReadSeq(long convId, long actorId, long lastReadSeq);

    Optional<ConversationMember> findMember(long convId, long actorId);

    boolean isMember(long convId, long actorId);

    /** 拉某人的会话列表（按最近活跃排序由实现决定）。 */
    List<Conversation> listByActor(long actorId, int limit);

    List<Long> listMemberIds(long convId, int limit);

    /**
     * 会话内序号自增（Redis 不可用时的兜底路径）。
     *
     * <p>正常路径走 Redis INCR；这里提供的是一次数据库自增，
     * 用于 Redis 故障时保证消息仍能落地且序号不冲突。
     */
    long nextSeqFromDb(long convId);
}
