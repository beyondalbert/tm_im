package com.tm.im.core.agent;

import com.tm.im.common.crypto.AgentCredentials;
import com.tm.im.common.crypto.Digests;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.common.id.IdGenerator;
import com.tm.im.common.json.Json;
import com.tm.im.core.identity.AccountService;
import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.entity.AgentProfile;
import com.tm.im.domain.entity.ActorSecret;
import com.tm.im.domain.enums.ActorStatus;
import com.tm.im.domain.enums.ActorType;
import com.tm.im.domain.enums.PushMode;
import com.tm.im.domain.enums.SecretType;
import com.tm.im.domain.repository.ActorRepository;
import com.tm.im.domain.repository.ActorSecretRepository;
import com.tm.im.domain.repository.AgentProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Agent 管理（03-rest-api.md §7 / 02-auth.md §3）—— 本项目的产品核心差异化。
 *
 * <h2>「对等」在这里是怎么落地的</h2>
 *
 * <p>创建出来的 Agent 就是一行 {@code actor}（{@code actor_type=2}），
 * 与人类走<b>同一套</b> handle 规则（{@link AccountService#normalizeHandle}）、
 * 同一张凭据表（{@code actor_secret}）、同一个鉴权入口
 * （{@code IdentityService.authenticate}，按 {@code sk_} 前缀分派）、
 * 同一套 REST 与长连接协议。因此「只改认证头即跑通」（M8 的验收标准）
 * 不是靠一个兼容层做到的，而是结构上只有一条路。
 *
 * <p><b>刻意不做「只有人类能创建 Agent」的校验</b>。那是 §11.5 的一句产品描述
 * （「账号由人类创建」），而实现它需要在领域层读 {@code actor_type} 做权限分支
 * ——DESIGN §0 的红线明确禁止（该字段只允许出现在「展示元数据」与「投递适配」两处）。
 * 允许一个 Agent 拥有另一个 Agent 的代价是「责任链可能有两级」，
 * 而 {@code agent_profile.owner_actor} 把这条链记下来了（出问题时能溯源）；
 * 若在领域层开这个分支，代价是红线破了一个口子，而口子只会越开越大。
 *
 * <h2>两把密钥的区别（这是最容易搞错的一处）</h2>
 *
 * <table border="1">
 *   <tr><th></th><th>api_key</th><th>webhook_secret</th></tr>
 *   <tr><td>用途</td><td>Agent 证明「我是谁」</td><td>平台与 Agent 互相证明「这条回调没被伪造」</td></tr>
 *   <tr><td>库里存什么</td><td>SHA-256 哈希（只用来反查 actor）</td><td><b>明文</b></td></tr>
 *   <tr><td>为什么</td><td>服务端从不需要它的明文：鉴权是「拿哈希查表」</td>
 *       <td>签名要算 HMAC，而 <b>HMAC 的密钥不能是哈希</b>——哈希不可逆，用它签出来的东西对方验不了</td></tr>
 *   <tr><td>失效方式</td><td>轮换（覆盖哈希）或账号停用（40301）</td><td>被删除（停用 Agent 时）</td></tr>
 * </table>
 *
 * <p>明文 webhook_secret 是一个<b>有意的取舍</b>：它意味着「拖库 = 能伪造回调」。
 * 替代方案是「用主密钥加密后落库」，但那只是把风险从数据库挪到主密钥上
 * （而主密钥同样在配置里），并且引入了密钥轮换时的重加密迁移。真正能降低风险的
 * 做法是「每个 Agent 一把、只用于验签、可以随时重签」——也就是现在的做法。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** 一个人类最多能拥有多少个 Agent。防的是「批量注册 Agent 刷资源」，不是产品配额。 */
    private static final int MAX_AGENTS_PER_OWNER = 50;

    private static final int MAX_CAPABILITIES = 16;
    private static final int MAX_CAPABILITY_LENGTH = 32;
    private static final int MAX_BIO_LENGTH = 512;
    private static final int MAX_ENDPOINT_LENGTH = 512;
    private static final int MAX_MODEL_INFO_LENGTH = 128;

    private final ActorRepository actors;
    private final ActorSecretRepository secrets;
    private final AgentProfileRepository profiles;
    private final IdGenerator idGenerator;
    private final AgentProperties properties;
    private final ZoneId databaseZone;

    public AgentService(ActorRepository actors,
                        ActorSecretRepository secrets,
                        AgentProfileRepository profiles,
                        IdGenerator idGenerator,
                        AgentProperties properties,
                        ZoneId databaseZone) {
        this.actors = actors;
        this.secrets = secrets;
        this.profiles = profiles;
        this.idGenerator = idGenerator;
        this.properties = properties;
        this.databaseZone = databaseZone;
    }

    // ================================================================== 创建

    /** 创建入参。{@code pushMode} 为 null 时由调用方（控制器）判定为「字段缺失」。 */
    public record CreateCommand(String handle,
                                String displayName,
                                String bio,
                                PushMode pushMode,
                                String endpointUrl,
                                List<String> capabilities,
                                String modelInfo,
                                Integer rateLimit) {
    }

    /**
     * 创建结果。
     *
     * <p>{@code apiKey} 与 {@code webhookSecret} <b>只在这一刻存在</b>：
     * api_key 之后只能轮换（库里只有哈希），webhook_secret 之后只能重签。
     * 调用方必须把这两件事写进响应，并且不能把它们放进任何缓存或日志。
     */
    public record Created(Actor actor, AgentProfile profile, String apiKey, String webhookSecret) {
    }

    /**
     * 创建一个归属 {@code ownerId} 的 Agent。
     *
     * <p>顺序：先把所有能失败的事做完（handle、push_mode、endpoint、capabilities、
     * 拥有者存在、数量上限），再开始写库。写库是「actor 行 + profile 行 + 凭据行」，
     * 中间任何失败都会留下一个「没有档案的 Agent」或「没有凭据的 Agent」，
     * 而后者表现为「创建成功但连不上」——所以整段在一个事务里。
     */
    @Transactional
    public Created create(long ownerId, CreateCommand cmd) {
        if (cmd.pushMode() == null) {
            // 控制器已经判过一次（40001），但服务是唯一入口：REST 与今后的
            // 其他链路（批导入、控制台脚本）都得经过这一关。没有默认模式是有意的，
            // 详见 AgentController#pushMode。
            throw new TmException(ErrorCode.MISSING_PARAMETER,
                    "push_mode 缺失（1=WEBHOOK 2=WS 3=PULL）");
        }
        // 拥有者必须存在（且没被停用）：不存在的话，创建出来的 Agent 是一个孤儿，
        // 而「谁负责它」是本模型里唯一能约束 Agent 的东西。
        Actor owner = actors.findById(ownerId).orElseThrow(
                () -> new TmException(ErrorCode.ACTOR_NOT_FOUND, "owner=" + ownerId));
        if (owner.getStatus() == ActorStatus.SUSPENDED) {
            // 停用的账号不能创建新身份——否则封禁只是「换个马甲」的事。
            throw new TmException(ErrorCode.ACCOUNT_SUSPENDED, "owner=" + ownerId);
        }
        List<AgentProfile> owned = profiles.findByOwner(ownerId, MAX_AGENTS_PER_OWNER + 1);
        if (owned.size() >= MAX_AGENTS_PER_OWNER) {
            throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "一个账号最多拥有 " + MAX_AGENTS_PER_OWNER + " 个 Agent（当前 " + owned.size() + "）");
        }

        String handle = AccountService.normalizeHandle(cmd.handle());
        String displayName = AccountService.normalizeDisplayName(cmd.displayName(), handle);
        String bio = truncateChecked(cmd.bio(), MAX_BIO_LENGTH, "bio");
        String endpoint = normalizeEndpoint(cmd.endpointUrl());
        String capabilities = normalizeCapabilities(cmd.capabilities());
        String modelInfo = truncateChecked(cmd.modelInfo(), MAX_MODEL_INFO_LENGTH, "model_info");
        int rateLimit = cmd.rateLimit() == null
                ? properties.getDefaultRateLimitPerMinute()
                : clampRateLimit(cmd.rateLimit());
        requireEndpointForWebhook(cmd.pushMode(), endpoint);

        if (actors.existsHandle(handle)) {
            throw new TmException(ErrorCode.HANDLE_EXISTS, "handle=" + handle);
        }

        LocalDateTime now = LocalDateTime.now(databaseZone);
        Actor agent = new Actor();
        agent.setId(idGenerator.nextId());
        agent.setActorType(ActorType.AGENT);
        agent.setHandle(handle);
        agent.setDisplayName(displayName);
        agent.setAvatarUrl(null);
        agent.setBio(bio);
        agent.setStatus(ActorStatus.ACTIVE);
        agent.setCreatedAt(now);
        try {
            actors.insert(agent);
        } catch (DuplicateKeyException e) {
            // 并发创建同名 Agent：对外与「预先查到已存在」完全一致（40005）。
            throw new TmException(ErrorCode.HANDLE_EXISTS, "handle=" + handle);
        }

        AgentProfile profile = new AgentProfile();
        profile.setActorId(agent.getId());
        profile.setOwnerActor(ownerId);
        profile.setEndpointUrl(endpoint);
        profile.setPushMode(cmd.pushMode());
        profile.setCapabilities(capabilities);
        profile.setModelInfo(modelInfo);
        profile.setRateLimit(rateLimit);
        profiles.save(profile);

        String apiKey = AgentCredentials.newApiKey();
        upsertSecret(agent.getId(), SecretType.API_KEY_HASH, Digests.sha256Hex(apiKey));

        // webhook_secret 只有 push_mode=WEBHOOK 才发：给一个永远不会被调用的密钥，
        // 只会让接收方以为自己的 Webhook 通道是通的。
        String webhookSecret = null;
        if (cmd.pushMode() == PushMode.WEBHOOK) {
            webhookSecret = AgentCredentials.newWebhookSecret();
            upsertSecret(agent.getId(), SecretType.WEBHOOK_SECRET, webhookSecret);
        }

        log.info("Agent 已创建 actorId={} handle={} owner={} pushMode={}",
                agent.getId(), handle, ownerId, cmd.pushMode());
        return new Created(agent, profile, apiKey, webhookSecret);
    }

    // ================================================================== 查询

    /** 列表/详情用的一行：Actor（展示信息）+ AgentProfile（投递配置）。 */
    public record AgentEntry(Actor actor, AgentProfile profile) {
    }

    /** 我创建的 Agent 列表（按 actor id 倒序＝创建时间倒序）。 */
    public List<AgentEntry> list(long ownerId) {
        List<AgentProfile> rows = profiles.findByOwner(ownerId, MAX_AGENTS_PER_OWNER);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Actor> byId = new HashMap<>();
        for (Actor actor : actors.findByIds(rows.stream().map(AgentProfile::getActorId).toList())) {
            byId.put(actor.getId(), actor);
        }
        List<AgentEntry> out = new ArrayList<>(rows.size());
        for (AgentProfile row : rows) {
            Actor actor = byId.get(row.getActorId());
            if (actor == null) {
                // profile 指向一个不存在的 actor：数据不一致。记 ERROR 并跳过，
                // 让列表其余部分仍可用（一个坏行不该让整个列表打不开）。
                log.error("agent_profile 指向不存在的 actor actorId={} —— 数据不一致", row.getActorId());
                continue;
            }
            out.add(new AgentEntry(actor, row));
        }
        return out;
    }

    /**
     * Agent 详情。
     *
     * <p><b>只有拥有者能看</b>（40302）：这些字段里有 webhook 地址与配额，
     * 属于「运维配置」而不是公开资料。任何人想看 Agent 的公开资料应当走
     * {@code GET /v1/actors/{id}}（§2.2）——那条路径是所有人都能走的，
     * 且不返回本类里的任何字段。
     */
    public AgentEntry detail(long ownerId, long actorId) {
        return requireOwned(ownerId, actorId);
    }

    // ================================================================== 修改

    /**
     * 可改字段的补丁。<b>null 表示「不改」</b>——这与 §1.3 的约定一致
     * （{@code PATCH} 类接口要能区分「不传」与「传 null」）。
     *
     * <p>本类<b>不支持把字段清空</b>（把 {@code endpoint_url} 改成 null）。
     * 那件事的正确表达方式是切 {@code push_mode}——用一个「协议字段」表达
     * 「我不再需要 Webhook 了」，比让 endpoint_url 处于「空但模式仍是 WEBHOOK」
     * 的半状态更容易解释。
     */
    public record Patch(String displayName, String bio, PushMode pushMode, String endpointUrl,
                        List<String> capabilities, String modelInfo, Integer rateLimit) {
    }

    @Transactional
    public AgentEntry update(long ownerId, long actorId, Patch patch) {
        AgentEntry entry = requireOwned(ownerId, actorId);
        Actor actor = entry.actor();
        AgentProfile profile = entry.profile();

        if (patch.displayName() != null) {
            actor.setDisplayName(AccountService.normalizeDisplayName(patch.displayName(), actor.getHandle()));
        }
        if (patch.bio() != null) {
            actor.setBio(truncateChecked(patch.bio(), MAX_BIO_LENGTH, "bio"));
        }
        if (patch.capabilities() != null) {
            profile.setCapabilities(normalizeCapabilities(patch.capabilities()));
        }
        if (patch.modelInfo() != null) {
            profile.setModelInfo(truncateChecked(patch.modelInfo(), MAX_MODEL_INFO_LENGTH, "model_info"));
        }
        if (patch.rateLimit() != null) {
            profile.setRateLimit(clampRateLimit(patch.rateLimit()));
        }
        if (patch.pushMode() != null) {
            profile.setPushMode(patch.pushMode());
        }
        if (patch.endpointUrl() != null) {
            profile.setEndpointUrl(normalizeEndpoint(patch.endpointUrl()));
        }
        // 校验的是「合并之后」的组合，而不是补丁里的字段：
        // 只改 push_mode 时 endpoint 来自已有配置，只改 endpoint 时模式来自已有配置。
        requireEndpointForWebhook(profile.getPushMode(), profile.getEndpointUrl());

        // Actor 的更新走「取出来改再写回」而不是 UPDATE 指定列：
        // 实体只有 6 个可写字段，而漏掉某个字段的 UPDATE 会静默丢掉它。
        actors.update(actor);
        profiles.save(profile);
        log.info("Agent 配置已更新 actorId={} owner={}", actorId, ownerId);
        return new AgentEntry(actor, profile);
    }

    /**
     * 轮换 api_key（02-auth.md §3.2）。旧的<b>立即失效</b>。
     *
     * <p>实现是「覆盖哈希」而不是「并存两把」：文档明确说旧 key 立即失效，
     * 而并存需要一张「活跃密钥」表与一个「哪把是当前」的概念——
     * 那是一个应该由 Agent 自己解决的问题（先切配置再轮换），
     * 而不是平台替它兜着（兜着的代价是「被吊销的密钥仍然能用」这个安全缺口）。
     */
    @Transactional
    public String rotateKey(long ownerId, long actorId) {
        AgentEntry entry = requireOwned(ownerId, actorId);
        if (entry.actor().getStatus() == ActorStatus.SUSPENDED) {
            // 停用的 Agent 不能轮换密钥：那会变成「先停用再换把钥匙继续用」。
            throw new TmException(ErrorCode.PERMISSION_DENIED,
                    "Agent 已停用，不能轮换密钥 actorId=" + actorId);
        }
        String apiKey = AgentCredentials.newApiKey();
        upsertSecret(actorId, SecretType.API_KEY_HASH, Digests.sha256Hex(apiKey));
        log.info("Agent api_key 已轮换 actorId={} owner={}（旧密钥立即失效）", actorId, ownerId);
        return apiKey;
    }

    /**
     * 停用 Agent（02-auth.md §3.4）：{@code status=2}，其所有凭证失效。
     *
     * <p><b>api_key 的哈希刻意<b>不删</b></b>：删掉之后，用它发来的请求会以
     * {@code 40105 invalid api key} 被拒——而那个码的客户端动作是「检查或轮换」，
     * 可 Agent 无权轮换（那需要拥有者的 JWT），于是它只会一直重试一个永远不会成功的动作。
     * 保留哈希则让鉴权能走到「账号已停用」那一步，回 {@code 40301}，
     * 而那个码的语义正是「停止重试、告诉用户」。这也与人类账号一致
     * （封禁后密码本身并没有变，变的是账号状态）——一致性本身就是「对等」的一部分。
     *
     * <p>webhook_secret 则真的删掉：它唯一的用途是签名，而停用之后不该再有任何事件投出去。
     */
    @Transactional
    public AgentEntry disable(long ownerId, long actorId) {
        AgentEntry entry = requireOwned(ownerId, actorId);
        Actor actor = entry.actor();
        if (actor.getStatus() != ActorStatus.SUSPENDED) {
            actor.setStatus(ActorStatus.SUSPENDED);
            actors.update(actor);
        }
        secrets.delete(actorId, SecretType.WEBHOOK_SECRET);
        log.info("Agent 已停用 actorId={} owner={}（api_key 保留哈希以便回 40301 而不是 40105）",
                actorId, ownerId);
        return new AgentEntry(actor, entry.profile());
    }

    // ================================================================== 内部

    private AgentEntry requireOwned(long ownerId, long actorId) {
        AgentProfile profile = profiles.find(actorId).orElseThrow(() -> new TmException(
                ErrorCode.ACTOR_NOT_FOUND, "actorId=" + actorId + " 不是 Agent"));
        if (!Objects.equals(profile.getOwnerActor(), ownerId)) {
            // 不区分「不存在」与「不是你的」之外的信息：回 40302 而不是 40401，
            // 因为 actorId 是雪花号、不可枚举，而「这不是你的 Agent」是客户端
            // 唯一能据此修正的动作（检查自己传的 id）。
            throw new TmException(ErrorCode.PERMISSION_DENIED,
                    "actorId=" + actorId + " 的拥有者是 " + profile.getOwnerActor()
                            + "，调用者是 " + ownerId);
        }
        Actor actor = actors.findById(actorId).orElseThrow(() -> new TmException(
                ErrorCode.ACTOR_NOT_FOUND, "actorId=" + actorId));
        return new AgentEntry(actor, profile);
    }

    private void upsertSecret(long actorId, SecretType type, String value) {
        ActorSecret secret = new ActorSecret();
        secret.setActorId(actorId);
        secret.setSecretType(type);
        secret.setSecretHash(value);
        secret.setLastUsedAt(null);
        secrets.upsert(secret);
    }

    /**
     * push_mode=WEBHOOK 必须有可投递的地址。
     *
     * <p>在创建与修改<b>两处</b>都判（判的是「合并后的组合」）：
     * 只在创建时判的话，一个 WS 模式的 Agent 可以被改成 WEBHOOK 而不给地址，
     * 而它的表现是「消息发出去了、Agent 没收到、两边日志都没有异常」——
     * 因为投递会因为「没有地址」而静默跳过。
     */
    private static void requireEndpointForWebhook(PushMode mode, String endpoint) {
        if (mode == PushMode.WEBHOOK && (endpoint == null || endpoint.isBlank())) {
            throw new TmException(ErrorCode.MISSING_PARAMETER,
                    "push_mode=WEBHOOK 必须提供 endpoint_url（否则平台无处投递）");
        }
    }

    private static String normalizeEndpoint(String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            return null;
        }
        String clean = endpointUrl.strip();
        if (clean.length() > MAX_ENDPOINT_LENGTH) {
            throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "endpoint_url 长度 " + clean.length() + " 超过上限 " + MAX_ENDPOINT_LENGTH);
        }
        // 只接受 http/https。理由不是「防止 SSRF 到内网」——那需要更细的地址策略，
        // 而这里的意思是：一个不会走的地址（ftp://、file://、随手写的一串字）
        // 应当在创建时报错，而不是在第一次投递时以「投递失败」的形式暴露。
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "endpoint_url 必须以 http:// 或 https:// 开头: " + clean);
        }
        return clean;
    }

    /**
     * 声明式能力：存成 JSON 数组文本（列类型是 JSON）。
     *
     * <p>它只用于展示与协商（「这个 Agent 会不会看图」），<b>不参与任何权限判断</b>：
     * 一个声明了 {@code ["text"]} 的 Agent 照样能发图片消息——能力是它<b>声明</b>的，
     * 不是平台<b>限制</b>的。若将来要让它变成限制，那需要一次显式的产品决策
     * （并且要考虑「声明漏了一项就被禁掉」的后果）。
     */
    private static String normalizeCapabilities(List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return null;
        }
        if (capabilities.size() > MAX_CAPABILITIES) {
            throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "capabilities 最多 " + MAX_CAPABILITIES + " 项");
        }
        List<String> cleaned = new ArrayList<>(capabilities.size());
        for (String capability : capabilities) {
            if (capability == null || capability.isBlank()) {
                continue;
            }
            String item = capability.strip().toLowerCase(java.util.Locale.ROOT);
            if (item.length() > MAX_CAPABILITY_LENGTH) {
                throw new TmException(ErrorCode.INVALID_PARAMETER,
                        "capability 长度超过 " + MAX_CAPABILITY_LENGTH + ": " + item);
            }
            if (!cleaned.contains(item)) {
                cleaned.add(item);
            }
        }
        return cleaned.isEmpty() ? null : Json.write(cleaned);
    }

    private static String truncateChecked(String value, int max, String field) {
        if (value == null) {
            return null;
        }
        String clean = value.strip();
        if (clean.length() > max) {
            throw new TmException(ErrorCode.INVALID_PARAMETER,
                    field + " 长度 " + clean.length() + " 超过上限 " + max);
        }
        return clean.isEmpty() ? null : clean;
    }

    /**
     * 速率配额的下界。
     *
     * <p>上限只取数据库列宽的量级，下界取 1：0 或负数意味着「这个 Agent 永远发不出消息」，
     * 而那不是运维想表达的意思（想停就用 DELETE 停用）。
     */
    private static int clampRateLimit(int rateLimit) {
        if (rateLimit < 1) {
            throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "rate_limit 必须为正整数（停用请用 DELETE /v1/agents/{id}）: " + rateLimit);
        }
        return Math.min(rateLimit, 100_000);
    }
}
