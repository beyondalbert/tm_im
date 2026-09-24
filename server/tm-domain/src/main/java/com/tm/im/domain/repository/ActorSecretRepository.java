package com.tm.im.domain.repository;

import com.tm.im.domain.entity.ActorSecret;
import com.tm.im.domain.enums.SecretType;

import java.util.Optional;

/**
 * 凭据仓储 —— 与人/Agent 无关，两类凭证走同一张表、同一个接口。
 *
 * <p><b>为什么存在 {@link #findActorIdByHash}</b>：这是本项目唯一「不知道 actor_id
 * 就要查凭据」的场景，而它恰好是最热的路径（长连接 AUTH、REST 每次请求）。
 * api_key 的格式是 {@code sk_live_<随机串>}，里面<b>不含</b> actor_id
 * （见 02-auth.md §3.1），服务端只能拿密钥的哈希反查账号。
 * 因此 {@code actor_secret.secret_hash} 上必须有唯一索引 —— 否则每次鉴权
 * 都是一次全表扫描。索引由 {@code deploy/sql/01-schema.sql} 定义。
 *
 * <p><b>刻意没有 {@code touchLastUsed}</b>：{@code last_used_at} 看起来该在每次
 * 鉴权时更新，但那等于把「每个请求一次写」加到全库最热的写路径上。
 * 需要它时应改成抽样更新（如 1/100 概率）或异步批量回写，而不是在这里
 * 提供一个会被顺手调用的方法。列先留着，写入策略等 M9 审计需求明确后再定。
 */
public interface ActorSecretRepository {

    /** 按主键（actor_id, secret_type）取凭据。 */
    Optional<ActorSecret> find(long actorId, SecretType secretType);

    /**
     * 按哈希反查归属的 actor。密钥无效（不存在）时返回 {@code Optional.empty()}，
     * <b>不区分</b>「没有这个密钥」与「密钥属于一个不存在/被停用的账号」——
     * 对外都是「密钥无效」，避免通过响应差异枚举出系统中哪些 actor_id 存在。
     */
    Optional<Long> findActorIdByHash(SecretType secretType, String secretHash);

    /** 新增或覆盖。同一 (actor_id, secret_type) 只保留一条，用于密钥轮换。 */
    void upsert(ActorSecret secret);

    /** 撤销某类凭据（如停用 Agent、改密码后作废旧哈希）。 */
    boolean delete(long actorId, SecretType secretType);
}
