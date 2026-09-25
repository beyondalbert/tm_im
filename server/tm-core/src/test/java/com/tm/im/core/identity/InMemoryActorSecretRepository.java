package com.tm.im.core.identity;

import com.tm.im.domain.entity.ActorSecret;
import com.tm.im.domain.enums.SecretType;
import com.tm.im.domain.repository.ActorSecretRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 内存版 {@link ActorSecretRepository}：键与真实表一致（actor_id + secret_type）。 */
class InMemoryActorSecretRepository implements ActorSecretRepository {

    private final Map<String, ActorSecret> rows = new LinkedHashMap<>();

    @Override
    public Optional<ActorSecret> find(long actorId, SecretType secretType) {
        return Optional.ofNullable(rows.get(key(actorId, secretType)));
    }

    @Override
    public Optional<Long> findActorIdByHash(SecretType secretType, String secretHash) {
        return rows.values().stream()
                .filter(s -> s.getSecretType() == secretType
                        && secretHash != null && secretHash.equals(s.getSecretHash()))
                .map(ActorSecret::getActorId)
                .findFirst();
    }

    @Override
    public void upsert(ActorSecret secret) {
        rows.put(key(secret.getActorId(), secret.getSecretType()), secret);
    }

    @Override
    public boolean delete(long actorId, SecretType secretType) {
        return rows.remove(key(actorId, secretType)) != null;
    }

    private static String key(long actorId, SecretType type) {
        return actorId + ":" + type;
    }
}
