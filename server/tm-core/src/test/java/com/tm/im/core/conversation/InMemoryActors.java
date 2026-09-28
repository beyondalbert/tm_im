package com.tm.im.core.conversation;

import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.enums.ActorStatus;
import com.tm.im.domain.enums.ActorType;
import com.tm.im.domain.repository.ActorRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 内存版参与者仓储（只够会话相关用例用）。
 *
 * <p>刻意手写而不是用 Mockito：本测试要断言的几乎都是「某次调用有没有发生」，
 * 手写替身把这些状态直接暴露成字段，读起来就是一句话（与 {@code MessageServiceTest}
 * 里那套替身同一取舍）。
 */
public class InMemoryActors implements ActorRepository {

    private final Map<Long, Actor> byId = new LinkedHashMap<>();

    /** 造一个人：id 与 handle 都由调用方给，避免测试里到处拼 handle 字符串。 */
    public Actor put(long id, String handle) {
        Actor actor = new Actor();
        actor.setId(id);
        actor.setHandle(handle);
        actor.setDisplayName(handle);
        actor.setActorType(ActorType.HUMAN);
        actor.setStatus(ActorStatus.ACTIVE);
        actor.setCreatedAt(LocalDateTime.of(2026, 1, 1, 8, 0));
        byId.put(id, actor);
        return actor;
    }

    @Override
    public Optional<Actor> findById(long actorId) {
        return Optional.ofNullable(byId.get(actorId));
    }

    @Override
    public Optional<Actor> findByHandle(String handle) {
        if (handle == null) {
            return Optional.empty();
        }
        String normalized = handle.toLowerCase(Locale.ROOT);
        return byId.values().stream()
                .filter(a -> normalized.equals(a.getHandle()))
                .findFirst();
    }

    @Override
    public boolean existsHandle(String handle) {
        return findByHandle(handle).isPresent();
    }

    @Override
    public Actor insert(Actor actor) {
        byId.put(actor.getId(), actor);
        return actor;
    }

    @Override
    public List<Actor> findByIds(List<Long> actorIds) {
        List<Actor> out = new ArrayList<>(actorIds.size());
        for (Long id : actorIds) {
            Actor actor = byId.get(id);
            if (actor != null) {
                out.add(actor);
            }
        }
        return out;
    }
    @Override
    public void update(Actor actor) {
        byId.put(actor.getId(), actor);
    }

    @Override
    public List<Actor> pageForAdmin(Long beforeId, int limit, ActorType actorType, ActorStatus status,
                                    String handlePrefix) {
        // 与真实现同一套谓词与同一套排序（id 倒序），只是把 SQL 换成流：
        // 替身与真实现一旦在这里分叉，后台列表的用例就会变成「验证替身」而不是验证规则。
        String prefix = handlePrefix == null || handlePrefix.isBlank()
                ? null : handlePrefix.toLowerCase(Locale.ROOT);
        return byId.values().stream()
                .filter(a -> beforeId == null || a.getId() < beforeId)
                .filter(a -> actorType == null || a.getActorType() == actorType)
                .filter(a -> status == null || a.getStatus() == status)
                .filter(a -> prefix == null || (a.getHandle() != null
                        && a.getHandle().toLowerCase(Locale.ROOT).startsWith(prefix)))
                .sorted((x, y) -> Long.compare(y.getId(), x.getId()))
                .limit(Math.max(1, limit))
                .toList();
    }
}
