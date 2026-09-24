package com.tm.im.storage.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tm.im.domain.entity.Friendship;
import com.tm.im.domain.enums.FriendshipStatus;
import com.tm.im.domain.repository.FriendshipRepository;
import com.tm.im.domain.support.PairKeys;
import com.tm.im.storage.mapper.FriendshipMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 好友关系仓储实现。
 *
 * <p><b>所有入参都过 {@link PairKeys#ordered}</b>。这不是多余的防御：
 * 关系以无序对存储（约定 {@code actor_a < actor_b}），若某处传反，
 * 查询会安静地查不到 → 返回「不是好友」→ 表现为「明明是好友却发不出消息」。
 * 归一化只做一次、只在这里做，是让这类 bug 无处容身的最低成本办法。
 */
@Repository
public class FriendshipRepositoryImpl implements FriendshipRepository {

    private final FriendshipMapper mapper;

    public FriendshipRepositoryImpl(FriendshipMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Friendship> find(long actorX, long actorY) {
        long[] p = PairKeys.ordered(actorX, actorY);
        return Optional.ofNullable(mapper.selectOne(Wrappers.<Friendship>lambdaQuery()
                .eq(Friendship::getActorA, p[0])
                .eq(Friendship::getActorB, p[1])
                .last("LIMIT 1")));
    }

    @Override
    public boolean areFriends(long actorX, long actorY) {
        if (PairKeys.isSelf(actorX, actorY)) {
            return false;
        }
        return find(actorX, actorY)
                .map(f -> f.getStatus() == FriendshipStatus.ACCEPTED)
                .orElse(false);
    }

    @Override
    @Transactional
    public void save(Friendship friendship) {
        long[] p = PairKeys.ordered(friendship.getActorA(), friendship.getActorB());
        friendship.setActorA(p[0]);
        friendship.setActorB(p[1]);
        friendship.setUpdatedAt(LocalDateTime.now());

        Optional<Friendship> existing = find(p[0], p[1]);
        if (existing.isPresent()) {
            mapper.update(null, Wrappers.<Friendship>lambdaUpdate()
                    .eq(Friendship::getActorA, p[0])
                    .eq(Friendship::getActorB, p[1])
                    .set(Friendship::getStatus, friendship.getStatus())
                    .set(Friendship::getUpdatedAt, friendship.getUpdatedAt()));
            return;
        }
        try {
            mapper.insert(friendship);
        } catch (DuplicateKeyException e) {
            // 并发发起：另一方已插入，退化为更新即可（幂等）
            mapper.update(null, Wrappers.<Friendship>lambdaUpdate()
                    .eq(Friendship::getActorA, p[0])
                    .eq(Friendship::getActorB, p[1])
                    .set(Friendship::getStatus, friendship.getStatus())
                    .set(Friendship::getUpdatedAt, friendship.getUpdatedAt()));
        }
    }

    @Override
    public void updateStatus(long actorX, long actorY, FriendshipStatus status) {
        long[] p = PairKeys.ordered(actorX, actorY);
        mapper.update(null, Wrappers.<Friendship>lambdaUpdate()
                .eq(Friendship::getActorA, p[0])
                .eq(Friendship::getActorB, p[1])
                .set(Friendship::getStatus, status)
                .set(Friendship::getUpdatedAt, LocalDateTime.now()));
    }

    /**
     * 列出好友 ID。
     *
     * <p>必须<b>双向查两遍</b>：关系只存一行，好友可能落在 {@code actor_a} 也可能落在
     * {@code actor_b}。这是「无序对只存一份」这个设计带来的唯一代价——
     * 换来的是不会出现 A→B 与 B→A 两条记录状态不一致的情况。
     *
     * <p>两次查询合成一次 IN 查询再合并，避免 N+1。
     */
    @Override
    public List<Long> listFriendIds(long actorId, int limit) {
        int cap = Math.max(1, limit);

        List<Long> asA = mapper.selectList(Wrappers.<Friendship>lambdaQuery()
                        .eq(Friendship::getActorA, actorId)
                        .eq(Friendship::getStatus, FriendshipStatus.ACCEPTED)
                        .last("LIMIT " + cap))
                .stream().map(Friendship::getActorB).toList();

        List<Long> asB = mapper.selectList(Wrappers.<Friendship>lambdaQuery()
                        .eq(Friendship::getActorB, actorId)
                        .eq(Friendship::getStatus, FriendshipStatus.ACCEPTED)
                        .last("LIMIT " + cap))
                .stream().map(Friendship::getActorA).toList();

        List<Long> all = new ArrayList<>(asA.size() + asB.size());
        all.addAll(asA);
        all.addAll(asB);
        return all.stream().distinct().limit(cap).toList();
    }
}
