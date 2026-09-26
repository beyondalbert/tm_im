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

    /**
     * 只更新可变资料字段。
     *
     * <p>用 {@code lambdaUpdate} 显式列出要写的四列，而不是 {@code updateById}：
     * 后者会写入实体上所有非 null 字段，包括 {@code handle} —— 于是一个「只改昵称」
     * 的 PUT 型调用在拿旧实体回写时会顺手把 handle 也写回去，
     * 而如果期间有人改过 handle，这里就会把它静默改回旧值。
     * 显式列出可写列，是把「哪些字段是身份、哪些是资料」写进代码里。
     */
    @Override
    @Transactional
    public void update(Actor actor) {
        mapper.update(null, Wrappers.<Actor>lambdaUpdate()
                .eq(Actor::getId, actor.getId())
                .set(Actor::getDisplayName, actor.getDisplayName())
                .set(Actor::getAvatarUrl, actor.getAvatarUrl())
                .set(Actor::getBio, actor.getBio())
                .set(Actor::getStatus, actor.getStatus()));
    }
}
