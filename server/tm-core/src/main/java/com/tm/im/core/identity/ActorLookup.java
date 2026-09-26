package com.tm.im.core.identity;

import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.repository.ActorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * 「@handle 或 actor_id」→ {@link Actor} 的解析 —— 对外接口里凡是接受「一个人」的地方都走它。
 *
 * <p><b>为什么要单独一个类</b>：REST 里到处都是「指向某人」的字段（
 * {@code POST /v1/conversations/direct} 的 {@code peer}、
 * {@code POST /v1/friends/requests} 的 {@code target}、
 * {@code POST /v1/conversations/group} 的 {@code members}），
 * 而 {@code GET /v1/actors/by-handle/{handle}} 也要同一套归一化。
 * 让每个控制器各写一遍「去掉 @、转小写、查库」，早晚会出现一处忘了转小写——
 * 而那个症状是「有的人能查到、有的人查不到」，取决于他当初注册时手打的字母大小写。
 *
 * <p><b>两种写法的歧义是被消除的，不是被猜的</b>：
 * <ul>
 *   <li>以 {@code @} 开头 → 一律按 handle；</li>
 *   <li>纯数字 → 一律按 actor_id；</li>
 *   <li>其余 → 40002，并在 detail 里说清两种写法。</li>
 * </ul>
 * 之所以要定这条规则：{@code 12345} 既可能是 handle（handle 允许数字，见
 * {@code AccountService} 的校验）也可能是一个 actor_id，而两者指向的人不同。
 * 猜错的后果是「消息发给了另一个人」——一个无法靠重试修复的错误。
 * 因此数字 handle 只能用 {@code @12345} 访问，这条规则写在 03-rest-api.md §1.7。
 *
 * <p><b>刻意不在这里判账号状态</b>：需要「这个人现在能不能用」的地方
 * （登录、发消息）各自有更具体的判断与更准确的错误码（40301 账号停用）。
 * 把状态检查混进「查一个人」会让「查资料」变成「查权限」，
 * 于是查一个已停用账号的资料会返回 403——而客户端只想画出那个人的历史头像。
 */
@Service
public class ActorLookup {

    private static final Logger log = LoggerFactory.getLogger(ActorLookup.class);

    /** 纯数字即 actor_id。用 {@code [0-9]} 而不是 {@code \d}：后者在 Unicode 下会匹配全角数字。 */
    private static final Pattern ACTOR_ID = Pattern.compile("[0-9]+");

    private final ActorRepository actors;

    public ActorLookup(ActorRepository actors) {
        this.actors = actors;
    }

    /**
     * 解析并查出这个人。
     *
     * @param ref {@code "@alice"}（handle）或 {@code "1001"}（actor_id）两种写法；
     *            不带 {@code @} 的 handle 会被拒，理由见类注释
     * @throws TmException 40001（空）、40002（写法无法识别 / 数字越界）、40004（handle 不合法）、
     *                     40401（查无此人）
     */
    public Actor require(String ref) {
        String raw = ref == null ? "" : ref.strip();
        if (raw.isEmpty()) {
            throw new TmException(ErrorCode.MISSING_PARAMETER, "需要指定对象：@handle 或 actor_id");
        }
        if (raw.startsWith("@")) {
            String bare = raw.substring(1).strip();
            if (bare.isEmpty()) {
                throw new TmException(ErrorCode.INVALID_HANDLE, "「@」后面没有 handle");
            }
            // 复用注册时那一套归一化（trim + 校验形状 + 转小写），而不是在这里
            // 再写一份正则：两份迟早会漂移，而漂移的表现是「注册时接受、查人时找不到」。
            String handle = AccountService.normalizeHandle(bare);
            return actors.findByHandle(handle)
                    .orElseThrow(() -> new TmException(ErrorCode.ACTOR_NOT_FOUND, "handle=" + handle));
        }
        if (ACTOR_ID.matcher(raw).matches()) {
            long actorId;
            try {
                actorId = Long.parseLong(raw);
            } catch (NumberFormatException e) {
                // 19 位以上的数字：超出 BIGINT，不可能是真实 id
                throw new TmException(ErrorCode.INVALID_PARAMETER, "actor_id 超出范围: " + raw);
            }
            return requireById(actorId);
        }
        throw new TmException(ErrorCode.INVALID_PARAMETER,
                "无法识别的对象 \"" + raw + "\"：以 @ 开头按 handle 解析（@alice），纯数字按 actor_id 解析（1001）");
    }

    /** 按 id 查并要求存在。 */
    public Actor requireById(long actorId) {
        if (actorId <= 0) {
            throw new TmException(ErrorCode.INVALID_PARAMETER, "actor_id 必须为正整数: " + actorId);
        }
        return actors.findById(actorId)
                .orElseThrow(() -> {
                    log.debug("查无此人 actorId={}", actorId);
                    return new TmException(ErrorCode.ACTOR_NOT_FOUND, "actorId=" + actorId);
                });
    }
}
