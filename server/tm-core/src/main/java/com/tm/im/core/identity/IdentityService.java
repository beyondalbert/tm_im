package com.tm.im.core.identity;

import com.tm.im.common.crypto.Digests;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.enums.ActorStatus;
import com.tm.im.domain.enums.SecretType;
import com.tm.im.domain.repository.ActorRepository;
import com.tm.im.domain.repository.ActorSecretRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 统一鉴权入口 —— <b>长连接与 REST 必须都走这里</b>。
 *
 * <p>两类凭证的分派只此一处：{@code sk_} 开头按 api_key 查哈希，
 * 其余按 JWT 验签。这样「人」与「Agent」在鉴权之后拿到的是同一个
 * {@link AuthContext}，下游无从分支。
 *
 * <p><b>关键顺序：先证明「你是谁」，再查「你现在还能不能用」。</b>
 * token 里的 {@code status} 之类的快照不可信（签发后可能被封禁），
 * 因此封禁状态每次都从库里读。这也是 token 有效期只有 2 小时的原因之一：
 * 状态变化的最坏生效延迟就是 token 有效期。
 */
@Service
public class IdentityService {

    private static final Logger log = LoggerFactory.getLogger(IdentityService.class);

    /**
     * 日志里凭证只留前缀。留 8 个字符足以让人能对着数据库/日志确认「是不是同一把钥匙」，
     * 又不足以还原出可用凭证 —— 完整 api_key 有 20+ 字符。
     */
    private static final int CREDENTIAL_LOG_PREFIX = 8;

    private final ActorRepository actorRepository;
    private final ActorSecretRepository actorSecretRepository;
    private final JwtTokenService tokenService;

    public IdentityService(ActorRepository actorRepository,
                           ActorSecretRepository actorSecretRepository,
                           JwtTokenService tokenService) {
        this.actorRepository = actorRepository;
        this.actorSecretRepository = actorSecretRepository;
        this.tokenService = tokenService;
    }

    /** 暴露给登录/刷新流程（M3 REST）使用，避免各处自己 new。 */
    public JwtTokenService tokens() {
        return tokenService;
    }

    /**
     * @param credential AUTH 帧的 token 字段 / REST 的 Bearer 值
     * @param deviceId   客户端自报设备标识，可为 null
     * @throws TmException 40101/40102/40103/40105/40301/40401，见 02-auth.md §4
     */
    public AuthContext authenticate(String credential, String deviceId) {
        CredentialKind kind = CredentialKind.of(credential);
        long actorId = switch (kind) {
            case API_KEY -> resolveByApiKey(credential);
            case JWT -> tokenService.verify(credential).actorId();
        };

        Actor actor = actorRepository.findById(actorId)
                .orElseThrow(() -> {
                    // 签名合法但账号不存在：只有「账号被删除、token 还没过期」
                    // 这一种可能。记 warn 而不是 info —— 它意味着有人拿着
                    // 一个已注销账号的凭证在连。
                    log.warn("鉴权时账号不存在 actorId={} kind={}", actorId, kind);
                    return new TmException(ErrorCode.ACTOR_NOT_FOUND, "actorId=" + actorId);
                });

        if (actor.getStatus() == ActorStatus.SUSPENDED) {
            throw new TmException(ErrorCode.ACCOUNT_SUSPENDED, "actorId=" + actorId);
        }

        log.debug("鉴权通过 actorId={} kind={} device={}", actor.getId(), kind, deviceId);
        return new AuthContext(actor.getId(), actor.getHandle(), actor.getActorType(), kind, deviceId);
    }

    /**
     * api_key → actorId。
     *
     * <p>查不到时统一返回 {@code 40105 INVALID_API_KEY}，<b>不区分</b>
     * 「密钥不存在」与「密钥存在但账号被停用」（后者在下一步才判定，且用的是
     * 账号状态而非密钥状态）。对外只有「无效」一种结果，避免把
     * actor_secret 表的规模当成可探测信息暴露出去。
     */
    private long resolveByApiKey(String credential) {
        if (credential == null || credential.length() <= CredentialKind.API_KEY_PREFIX.length()) {
            throw new TmException(ErrorCode.INVALID_API_KEY, "长度不足");
        }
        String hash = Digests.sha256Hex(credential);
        Optional<Long> actorId = actorSecretRepository.findActorIdByHash(SecretType.API_KEY_HASH, hash);
        if (actorId.isEmpty()) {
            log.info("api_key 鉴权失败，无匹配凭据 prefix={}", safePrefix(credential));
            throw new TmException(ErrorCode.INVALID_API_KEY, "prefix=" + safePrefix(credential));
        }
        return actorId.get();
    }

    private static String safePrefix(String credential) {
        if (credential == null) {
            return "<null>";
        }
        return credential.length() <= CREDENTIAL_LOG_PREFIX
                ? credential
                : credential.substring(0, CREDENTIAL_LOG_PREFIX);
    }
}
