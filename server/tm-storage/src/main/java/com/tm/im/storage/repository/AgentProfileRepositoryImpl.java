package com.tm.im.storage.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tm.im.domain.entity.AgentProfile;
import com.tm.im.domain.repository.AgentProfileRepository;
import com.tm.im.storage.mapper.AgentProfileMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Agent 扩展信息仓储实现。
 *
 * <p>{@code agent_profile} 是非分片表（分片表只有 {@code message}），所以这里全是
 * 主键点查与一次索引查找——没有「不带分片键就广播 16 张表」的问题
 * （DESIGN §8.7），也不需要事务。
 *
 * <p>{@link #findByIds} 用 {@code selectBatchIds}（一次 {@code IN} 查询）而不是循环点查：
 * 调用方是消息扇出路径，而那条路径上「每多一次查询」的代价会乘以群成员数。
 */
@Repository
public class AgentProfileRepositoryImpl implements AgentProfileRepository {

    private final AgentProfileMapper mapper;

    public AgentProfileRepositoryImpl(AgentProfileMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<AgentProfile> find(long actorId) {
        return Optional.ofNullable(mapper.selectById(actorId));
    }

    @Override
    public List<AgentProfile> findByIds(List<Long> actorIds) {
        if (actorIds == null || actorIds.isEmpty()) {
            return List.of();
        }
        return mapper.selectBatchIds(actorIds);
    }

    @Override
    public List<AgentProfile> findByOwner(long ownerActor, int limit) {
        return mapper.selectList(Wrappers.<AgentProfile>lambdaQuery()
                .eq(AgentProfile::getOwnerActor, ownerActor)
                // 显式排序：不排序时顺序由存储引擎决定（恰好是主键序），
                // 换索引或换版本就会变，而客户端的分页会因此错位。
                .orderByDesc(AgentProfile::getActorId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    @Override
    public void save(AgentProfile profile) {
        if (mapper.selectById(profile.getActorId()) == null) {
            mapper.insert(profile);
            return;
        }
        // 更新而不是 delete+insert：agent_profile 的每一列都可能被 PATCH 改，
        // 而 insertOrUpdate 的语义在这里就是「以入参为准」。
        mapper.updateById(profile);
    }
}
