package com.tm.im.core.agent;

import com.tm.im.domain.entity.AgentProfile;
import com.tm.im.domain.repository.AgentProfileRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存 Agent 档案仓储替身。
 *
 * <p>它把「插入」与「更新」分开记（{@link #inserted} / {@link #updated}），
 * 因为被测代码里这两条路径的语义不同（创建 vs PATCH），而只断言最终状态
 * 会让「本该更新却走了插入」这种错（表现为主键冲突或静默覆盖）看不出来。
 */
public class InMemoryAgentProfiles implements AgentProfileRepository {

    private final Map<Long, AgentProfile> rows = new LinkedHashMap<>();
    private final List<String> calls = new ArrayList<>();

    @Override
    public Optional<AgentProfile> find(long actorId) {
        calls.add("find:" + actorId);
        return Optional.ofNullable(rows.get(actorId));
    }

    @Override
    public List<AgentProfile> findByIds(List<Long> actorIds) {
        calls.add("findByIds:" + actorIds.size());
        List<AgentProfile> out = new ArrayList<>();
        for (Long id : actorIds) {
            AgentProfile row = rows.get(id);
            if (row != null) {
                out.add(row);
            }
        }
        return out;
    }

    @Override
    public List<AgentProfile> findByOwner(long ownerActor, int limit) {
        calls.add("findByOwner:" + ownerActor);
        return rows.values().stream()
                .filter(row -> row.getOwnerActor() != null && row.getOwnerActor() == ownerActor)
                .sorted((x, y) -> Long.compare(y.getActorId(), x.getActorId()))
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public void save(AgentProfile profile) {
        boolean exists = rows.containsKey(profile.getActorId());
        calls.add((exists ? "update:" : "insert:") + profile.getActorId());
        rows.put(profile.getActorId(), profile);
    }

    /** 直接塞一行（构造「别人的 Agent」「不存在 profile」等前置状态）。 */
    public void seed(AgentProfile profile) {
        rows.put(profile.getActorId(), profile);
    }

    public AgentProfile get(long actorId) {
        return rows.get(actorId);
    }

    public int size() {
        return rows.size();
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }
}
