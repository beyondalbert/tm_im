package com.tm.im.domain.repository;

import com.tm.im.domain.entity.Actor;

import java.util.List;
import java.util.Optional;

/**
 * 参与者仓储 —— <b>人与 Agent 共用同一个接口</b>。
 *
 * <p>这里没有任何 {@code findHuman*} / {@code findAgent*} 方法，是刻意的：
 * 「对等」若只停留在文档里，第一次需要「只查人」的需求出现时就会破功。
 * 因此本接口不提供按 {@code actorType} 过滤的查询入口；
 * 需要 Agent 扩展信息（webhook、推送模式）时走 {@code AgentProfileRepository}，
 * 而不是把 Actor 拆成两套。
 */
public interface ActorRepository {

    Optional<Actor> findById(long actorId);

    Optional<Actor> findByHandle(String handle);

    boolean existsHandle(String handle);

    /** 插入并回填主键（主键由 Snowflake 生成，不由数据库自增）。 */
    Actor insert(Actor actor);

    /** 批量查询，用于消息推送时补齐发送者展示信息（避免 N+1）。 */
    List<Actor> findByIds(List<Long> actorIds);
}
