package com.tm.im.core.conversation;

import com.tm.im.domain.entity.Conversation;
import com.tm.im.domain.entity.ConversationMember;
import com.tm.im.domain.enums.MemberRole;
import com.tm.im.domain.repository.ConversationMembership;
import com.tm.im.domain.repository.ConversationRepository;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 内存版会话仓储。语义与 {@code ConversationRepositoryImpl} 对齐（幂等、原子建群、只前进的已读游标）。 */
class InMemoryConversations implements ConversationRepository {

    private final Map<Long, Conversation> conversations = new LinkedHashMap<>();
    private final Map<Long, List<ConversationMember>> members = new LinkedHashMap<>();
    private final List<String> calls = new ArrayList<>();

    /** 下一次 insert 抛唯一键冲突（模拟「两个请求同时建同一个单聊」）。 */
    boolean failNextInsertWithDuplicatePairKey;

    /** 下一次转让群主返回 false（模拟「检查通过之后、写入之前，另一个请求已经把群主转走了」）。 */
    boolean failNextTransferOwnership;
    long seqCounter;

    void put(Conversation conversation) {
        conversations.put(conversation.getId(), conversation);
        members.putIfAbsent(conversation.getId(), new ArrayList<>());
    }

    void putMember(long convId, long actorId, MemberRole role, long lastReadSeq) {
        ConversationMember member = new ConversationMember();
        member.setConvId(convId);
        member.setActorId(actorId);
        member.setRole(role);
        member.setLastReadSeq(lastReadSeq);
        member.setMuted(false);
        member.setJoinedAt(LocalDateTime.of(2026, 1, 1, 8, 0));
        members.computeIfAbsent(convId, k -> new ArrayList<>()).add(member);
    }

    ConversationMember member(long convId, long actorId) {
        return members.getOrDefault(convId, List.of()).stream()
                .filter(m -> m.getActorId() == actorId)
                .findFirst()
                .orElse(null);
    }

    List<ConversationMember> rows(long convId) {
        return List.copyOf(members.getOrDefault(convId, List.of()));
    }

    List<String> calls() {
        return List.copyOf(calls);
    }

    @Override
    public Optional<Conversation> findById(long convId) {
        calls.add("findById:" + convId);
        return Optional.ofNullable(conversations.get(convId));
    }

    @Override
    public Optional<Conversation> findDirectByPairKey(String pairKey) {
        calls.add("findDirectByPairKey:" + pairKey);
        return conversations.values().stream()
                .filter(c -> pairKey != null && pairKey.equals(c.getPairKey()))
                .findFirst();
    }

    @Override
    public Conversation insert(Conversation conversation) {
        calls.add("insert:" + conversation.getId());
        if (failNextInsertWithDuplicatePairKey) {
            failNextInsertWithDuplicatePairKey = false;
            throw new DuplicateKeyException("uk_pair_key");
        }
        put(conversation);
        return conversation;
    }

    @Override
    public void createGroup(Conversation conversation, List<ConversationMember> rows) {
        calls.add("createGroup:" + conversation.getId() + ":" + rows.size());
        put(conversation);
        for (ConversationMember row : rows) {
            row.setConvId(conversation.getId());
            members.get(conversation.getId()).add(row);
        }
    }

    @Override
    public boolean addMember(long convId, long actorId, MemberRole role, LocalDateTime joinedAt) {
        calls.add("addMember:" + convId + ":" + actorId);
        List<ConversationMember> list = members.computeIfAbsent(convId, k -> new ArrayList<>());
        if (list.stream().anyMatch(m -> m.getActorId() == actorId)) {
            return false;
        }
        ConversationMember member = new ConversationMember();
        member.setConvId(convId);
        member.setActorId(actorId);
        member.setRole(role);
        member.setLastReadSeq(0L);
        member.setMuted(false);
        member.setJoinedAt(joinedAt);
        list.add(member);
        return true;
    }

    @Override
    public void updateLastReadSeq(long convId, long actorId, long lastReadSeq) {
        // 与真实实现同一条不变量：只前进。少了这个判断，「乱序上报」用例会假通过。
        ConversationMember member = member(convId, actorId);
        if (member != null && member.getLastReadSeq() != null && member.getLastReadSeq() < lastReadSeq) {
            member.setLastReadSeq(lastReadSeq);
        }
    }

    @Override
    public boolean removeMember(long convId, long actorId) {
        calls.add("removeMember:" + convId + ":" + actorId);
        return members.getOrDefault(convId, new ArrayList<>())
                .removeIf(m -> m.getActorId() == actorId);
    }

    @Override
    public boolean updateMemberRole(long convId, long actorId, MemberRole role) {
        calls.add("updateMemberRole:" + convId + ":" + actorId + ":" + role.code());
        ConversationMember member = member(convId, actorId);
        if (member == null) {
            return false;
        }
        member.setRole(role);
        return true;
    }

    /**
     * 真实的实现靠 {@code @Transactional} 保证三行一起改；内存版把它们写成相邻的三行。
     *
     * <p>单测能验的是「调用方拿到的返回值和会话行是什么」，验不了回滚
     * （那是 {@code ConversationReadPathIT} 在真库上的事）。
     */
    @Override
    public boolean transferOwnership(long convId, long fromActorId, long toActorId) {
        calls.add("transferOwnership:" + convId + ":" + fromActorId + "->" + toActorId);
        if (failNextTransferOwnership) {
            failNextTransferOwnership = false;
            return false;
        }
        ConversationMember from = member(convId, fromActorId);
        if (from == null || from.getRole() != MemberRole.OWNER) {
            return false;
        }
        ConversationMember to = member(convId, toActorId);
        if (to == null) {
            throw new IllegalStateException("转让群主：目标成员行不存在 actorId=" + toActorId);
        }
        from.setRole(MemberRole.ADMIN);
        to.setRole(MemberRole.OWNER);
        Conversation conversation = conversations.get(convId);
        if (conversation == null || !Objects.equals(conversation.getOwnerActor(), fromActorId)) {
            throw new IllegalStateException("转让群主：conversation.owner_actor 不是 " + fromActorId);
        }
        conversation.setOwnerActor(toActorId);
        return true;
    }

    @Override
    public boolean updateTitle(long convId, String title) {
        calls.add("updateTitle:" + convId);
        Conversation conversation = conversations.get(convId);
        if (conversation == null) {
            return false;
        }
        conversation.setTitle(title);
        return true;
    }

    @Override
    public Optional<ConversationMember> findMember(long convId, long actorId) {
        return Optional.ofNullable(member(convId, actorId));
    }

    @Override
    public boolean isMember(long convId, long actorId) {
        return member(convId, actorId) != null;
    }

    @Override
    public List<ConversationMembership> listMemberships(long actorId, int maxScan) {
        calls.add("listMemberships:" + actorId);
        List<ConversationMembership> out = new ArrayList<>();
        for (Conversation conversation : conversations.values()) {
            ConversationMember member = member(conversation.getId(), actorId);
            if (member != null) {
                out.add(new ConversationMembership(conversation, member));
            }
        }
        if (maxScan > 0 && out.size() > maxScan) {
            return out.subList(0, maxScan);
        }
        return out;
    }

    @Override
    public List<Long> listMemberIds(long convId, int limit) {
        return members.getOrDefault(convId, List.of()).stream()
                .map(ConversationMember::getActorId)
                .sorted()
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public List<ConversationMember> listMembers(long convId, int limit) {
        return members.getOrDefault(convId, List.of()).stream()
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public long countMembers(long convId) {
        return members.getOrDefault(convId, List.of()).size();
    }

    @Override
    public long nextSeq(long convId) {
        return ++seqCounter;
    }

    @Override
    public void raiseSeqFloor(long convId, long floor) {
        throw new UnsupportedOperationException("会话读写用例不涉及取号自愈");
    }

    @Override
    public long nextSeqFromDb(long convId, long floor) {
        throw new UnsupportedOperationException();
    }
}
