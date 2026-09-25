package com.tm.im.core.identity;

import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.repository.ActorRepository;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存版 {@link ActorRepository}，供 {@code AccountServiceTest} 用。
 *
 * <p>手写替身而不是 Mockito：本项目已有的替身（{@code RecordingPushPort}、
 * {@code RecordingNodeRegistry}）都是手写的，理由是<b>替身自己也要能被读懂</b>——
 * mock 的 {@code when(...)} 串读起来是「测试断言了什么」和「实现怎么调用」混在一起，
 * 而手写替身把它压回「一个最小的真实实现」。
 *
 * <p>{@link #failNextInsertWithDuplicateKey()} 是这里唯一不那么「真实」的部分：
 * 它用来复现「两个并发注册同时通过 existsHandle 检查」那一刻，
 * 而那个窗口没法在单测里稳定地制造出来（真正的唯一性由 {@code uk_handle} 保证）。
 */
class InMemoryActorRepository implements ActorRepository {

    private final Map<Long, Actor> byId = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong(1000);
    private boolean failNextInsertWithDuplicateKey;
    private int insertCount;

    void failNextInsertWithDuplicateKey() {
        this.failNextInsertWithDuplicateKey = true;
    }

    int insertCount() {
        return insertCount;
    }

    @Override
    public Optional<Actor> findById(long actorId) {
        return Optional.ofNullable(byId.get(actorId));
    }

    @Override
    public Optional<Actor> findByHandle(String handle) {
        // 大小写不敏感，与目标实例的排序规则 utf8mb4_0900_ai_ci 一致。
        // 这里若做成精确匹配，测试就会「证明」一个生产上不成立的行为。
        return byId.values().stream()
                .filter(a -> a.getHandle() != null && a.getHandle().equalsIgnoreCase(handle))
                .findFirst();
    }

    @Override
    public boolean existsHandle(String handle) {
        return findByHandle(handle).isPresent();
    }

    @Override
    public Actor insert(Actor actor) {
        insertCount++;
        if (failNextInsertWithDuplicateKey) {
            failNextInsertWithDuplicateKey = false;
            throw new DuplicateKeyException("uk_handle 冲突（模拟并发注册）");
        }
        if (actor.getId() == null) {
            actor.setId(ids.incrementAndGet());
        }
        if (existsHandle(actor.getHandle())) {
            throw new DuplicateKeyException("uk_handle 冲突: " + actor.getHandle());
        }
        byId.put(actor.getId(), actor);
        return actor;
    }

    @Override
    public List<Actor> findByIds(List<Long> actorIds) {
        List<Actor> out = new ArrayList<>();
        for (Long id : actorIds) {
            Actor a = byId.get(id);
            if (a != null) {
                out.add(a);
            }
        }
        out.sort(Comparator.comparing(Actor::getId));
        return out;
    }
}
