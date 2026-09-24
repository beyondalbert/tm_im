package com.tm.im.storage.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.repository.ActorRepository;
import com.tm.im.storage.mapper.ActorMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public class ActorRepositoryImpl implements ActorRepository {

    private final ActorMapper mapper;

    public ActorRepositoryImpl(ActorMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Actor> findById(long actorId) {
        return Optional.ofNullable(mapper.selectById(actorId));
    }

    @Override
    public Optional<Actor> findByHandle(String handle) {
        return Optional.ofNullable(
                mapper.selectOne(Wrappers.<Actor>lambdaQuery().eq(Actor::getHandle, handle).last("LIMIT 1")));
    }

    @Override
    public boolean existsHandle(String handle) {
        return mapper.exists(Wrappers.<Actor>lambdaQuery().eq(Actor::getHandle, handle));
    }

    @Override
    @Transactional
    public Actor insert(Actor actor) {
        // 主键由调用方用 Snowflake 生成后填入；IdType.INPUT 意味着这里不做任何补号。
        // 好处是 ID 在事务提交前就已知，可用于幂等与日志关联。
        mapper.insert(actor);
        return actor;
    }

    @Override
    public List<Actor> findByIds(List<Long> actorIds) {
        if (actorIds == null || actorIds.isEmpty()) {
            return List.of();
        }
        // selectBatchIds 而非循环 selectById：推送一条消息要补发送者信息，
        // 群聊场景下循环查会产生 N 次往返（N+1 查询）。
        return mapper.selectBatchIds(actorIds);
    }
}
