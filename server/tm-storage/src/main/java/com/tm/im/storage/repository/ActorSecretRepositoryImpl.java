package com.tm.im.storage.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tm.im.domain.entity.ActorSecret;
import com.tm.im.domain.enums.SecretType;
import com.tm.im.domain.repository.ActorSecretRepository;
import com.tm.im.storage.mapper.ActorSecretMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public class ActorSecretRepositoryImpl implements ActorSecretRepository {

    private final ActorSecretMapper mapper;

    public ActorSecretRepositoryImpl(ActorSecretMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ActorSecret> find(long actorId, SecretType secretType) {
        // 本表是联合主键 (actor_id, secret_type)，不能用 selectById ——
        // 实体的 selectById 会退化成 WHERE actor_id = ?，多行里随便返回一行。
        return Optional.ofNullable(mapper.selectOne(
                Wrappers.<ActorSecret>lambdaQuery()
                        .eq(ActorSecret::getActorId, actorId)
                        .eq(ActorSecret::getSecretType, secretType)));
    }

    @Override
    public Optional<Long> findActorIdByHash(SecretType secretType, String secretHash) {
        if (secretHash == null || secretHash.isBlank()) {
            // 空哈希直接返回 empty：否则会拼出 secret_hash = '' 的条件，
            // 虽然也不匹配任何行，但会让「调用方传了空值」这一缺陷静默通过。
            return Optional.empty();
        }
        ActorSecret found = mapper.selectOne(
                Wrappers.<ActorSecret>lambdaQuery()
                        .eq(ActorSecret::getSecretType, secretType)
                        .eq(ActorSecret::getSecretHash, secretHash)
                        .last("LIMIT 1"));
        return found == null ? Optional.empty() : Optional.of(found.getActorId());
    }

    @Override
    @Transactional
    public void upsert(ActorSecret secret) {
        if (secret == null || secret.getActorId() == null || secret.getSecretType() == null) {
            throw new IllegalArgumentException("upsert 需要 actorId 与 secretType");
        }
        int updated = mapper.update(secret,
                Wrappers.<ActorSecret>lambdaUpdate()
                        .eq(ActorSecret::getActorId, secret.getActorId())
                        .eq(ActorSecret::getSecretType, secret.getSecretType()));
        if (updated == 0) {
            // 密钥轮换的常见路径是「整行替换」，先 UPDATE 再 INSERT 比
            // 先查后写少一次往返；唯一索引 uk_secret_hash 兜住并发下的重复插入。
            mapper.insert(secret);
        }
    }

    @Override
    @Transactional
    public boolean delete(long actorId, SecretType secretType) {
        return mapper.delete(Wrappers.<ActorSecret>lambdaQuery()
                .eq(ActorSecret::getActorId, actorId)
                .eq(ActorSecret::getSecretType, secretType)) > 0;
    }
}
