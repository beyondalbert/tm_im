package com.tm.im.domain.repository;

import java.time.Duration;
import java.util.Optional;

/**
 * refresh_token 的存放（02-auth.md §2.3：<b>一次性、轮换</b>）。
 *
 * <p><b>为什么这个接口在 tm-domain 而不是「定义它的那个业务模块」</b>：
 * 本项目的依赖方向是单向的（DESIGN §12）：tm-common ← tm-domain ← tm-storage
 * ← tm-core。接口必须放在实现方能看见的那一层——实现（Redis）在 tm-storage，
 * 所以接口在 tm-domain。这与 {@code ConversationRepository}（接口在 tm-domain、
 * 实现在 tm-storage）同构；而 {@code MessagePushPort} 能放在 tm-core 里，
 * 是因为它的实现（tm-channel）在 tm-core <b>之上</b>。
 * 这条规则看似琐碎，但放错的位置会以「无法解析 com.tm.im.core.identity」
 * 这种与设计毫无关系的编译错误暴露出来。
 *
 * <p><b>为什么是「消费」而不是「查询」</b>：文档要求旧的 refresh_token 一旦用过
 * 就立刻失效，这是防重放的核心。若接口是 {@code find()} + {@code revoke()} 两步，
 * 两个并发请求可以都读到同一个凭证、都换成新的——这正是重放攻击的样子，
 * 而它对应用层表现为「偶发」的，永远无法通过重试复现。
 * 因此对外只有 {@link #consume}，实现必须<b>原子地</b>完成「取走并删除」。
 *
 * <p><b>为什么没有 {@code listSessions(actorId)} / {@code revokeAll(actorId)}</b>：
 * 「查看我的登录设备」「改密码后踢掉所有会话」是另外两个功能（M9），
 * 它们的存储形态（要不要记 IP/UA、要不要按设备聚合）尚未定。
 * 提前加一个返回 List 的方法会诱使调用方把 Redis 当数据库用；
 * 而 {@code revokeAll} 要求为每个 actor 维护一个「他签过哪些 token」的集合——
 * 那是一条额外的写路径，得先想清楚什么时机写、集合本身何时过期。
 */
public interface RefreshTokenStore {

    /**
     * 签发并存下。
     *
     * @param ttl 存活时间。用 TTL 而不是「过期时间字段 + 定时清理」：
     *            没有清理任务时，后者会堆积成一张永不收缩的表。
     * @return 明文凭证，仅此一次返回。
     */
    String issue(long actorId, String deviceId, Duration ttl);

    /**
     * 原子地取走并作废。
     *
     * @return 命中的会话；凭证不存在、已用过、已过期都是 {@link Optional#empty()} ——
     *         三者对外同为 40104（02-auth.md §4），区分它们只会泄漏「这个凭证曾经存在过」。
     */
    Optional<RefreshSession> consume(String plainToken);

    /**
     * 只读地看一眼，不消耗。
     *
     * <p>存在的原因只有一个：登出时得先确认「这个 refresh_token 是属于调用者的」。
     * 没有它，{@link #revoke} 就成了一个「凭什么凭证都能作废」的接口——
     * 而拿到别人凭证的人本来就能用它换出新令牌，让帮凶多一个删凭证的能力
     * 只会把「凭证泄露」升级成「凭证泄露 + 受害者被登出」。
     *
     * <p>与 {@link #consume} 不同，这里
     * <b>允许</b>「先读再写」两步：作废是幂等的，即使两步之间凭证被刷新走，
     * 最坏结果也只是「新凭证还在」——用户可以真的重登一次，而不是被错误地
     * 告知「已退出」。
     */
    Optional<RefreshSession> peek(String plainToken);

    /**
     * 主动作废（登出）。凭证不存在时静默返回：登出必须是幂等的——
     * 客户端在弱网下重试登出请求是常态，第二次失败会让它把用户留在
     * 「看起来没退出」的状态里。
     */
    void revoke(String plainToken);
}
