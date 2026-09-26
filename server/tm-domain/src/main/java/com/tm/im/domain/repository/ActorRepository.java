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

    /**
     * 更新可变的资料字段（{@code display_name} / {@code avatar_url} / {@code bio} / {@code status}）。
     *
     * <p>刻意不是「更新全部字段」：{@code id} / {@code actor_type} / {@code handle} /
     * {@code created_at} 都是不可变的（改 handle 等于换个人，而 ID 与类型改了就无从
     * 解释已有数据），把它们也放进 UPDATE 只是多几个永远不会用的写列。
     *
     * <p>实现约定：缺失的行（{@code actor} 不存在）静默不做事——它不改变调用方的
     * 可观察结果（更新一个不存在的行，结果就是什么都没有变），而抛异常会把
     * 「账号被并发删了」变成一次 500。
     */
    void update(Actor actor);

    /** 批量查询，用于消息推送时补齐发送者展示信息（避免 N+1）。 */
    List<Actor> findByIds(List<Long> actorIds);
}
