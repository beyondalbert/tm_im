package com.tm.im.domain.repository;

import com.tm.im.domain.entity.AgentProfile;

import java.util.List;
import java.util.Optional;

/**
 * Agent 扩展信息仓储（{@code agent_profile} 表）—— <b>对等模型的那个「例外」</b>。
 *
 * <p>只有一张 {@code actor} 表，但 Agent 多出一张扩展表（webhook 地址、推送模式、
 * 声明式能力）。这不是「Agent 专属表」那条红线的违反，而是它允许的
 * 「展示元数据 + 投递适配」：本表里的每一列都只影响「消息怎么送到这个 Actor」
 * 或「界面上怎么描述它」，<b>没有一列影响它能不能发消息、能进哪个群</b>。
 *
 * <p>判断一个字段该不该进这张表的判据因此是：「去掉它，Agent 与人还同权吗？」
 * 若不同权，那它就属于 {@code actor} 或领域规则，不属于这里。
 *
 * <p><b>{@link #findByOwner} 是这张表唯一的「列表」查询</b>：Agent 归属唯一一个
 * 「拥有者」（{@code owner_actor}），因此「我创建的 Agent」是一次索引查找
 * （{@code idx_owner}），不需要跨表 JOIN。
 */
public interface AgentProfileRepository {

    Optional<AgentProfile> find(long actorId);

    /**
     * 一次取多个 Agent 的扩展信息。
     *
     * <p>推送路径要用它来回答「这批成员里谁需要 Webhook 投递」，而且必须是
     * <b>一次查询</b>：群消息的扇出是按成员循环的，逐个查会让一条群消息变成
     * N 次查询（500 人群 = 500 次），而那正是 DESIGN §10.3 分级要避免的量级。
     */
    List<AgentProfile> findByIds(List<Long> actorIds);

    List<AgentProfile> findByOwner(long ownerActor, int limit);

    /** 新增或覆盖（主键是 actor_id）。 */
    void save(AgentProfile profile);
}
