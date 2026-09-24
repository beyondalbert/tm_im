package com.tm.im.domain.policy;

import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.domain.enums.ConvType;
import com.tm.im.domain.enums.FriendshipStatus;

/**
 * 发消息准入规则 —— 产品硬规则的唯一实现处。
 *
 * <p><b>规则（DESIGN §11.6）</b>：非好友不能发消息。这是一条产品级拦截，
 * 不是风控兜底。它同时也解释了为什么系统敢放开 Agent 注册：
 * 好友制天然限制了 Agent 能骚扰谁——它只能给自己被加为好友的对象发消息。
 *
 * <pre>
 *   单聊（DIRECT）：必须 是会话成员 且 双方好友关系为 ACCEPTED
 *   群聊（GROUP） ：必须 是会话成员（不校验好友关系）
 * </pre>
 *
 * <p><b>为什么群聊豁免</b>：群聊里逐条校验「发信人与每个成员是否为好友」意味着
 * N 次好友关系查询，500 人群就是 500 次查询；而且产品语义上也说不通——
 * 被拉进一个群，却被要求先加所有人好友才能发言。因此群聊只认成员身份，
 * 骚扰问题由「谁能拉人进群」和退群机制解决。
 *
 * <p><b>已与用户确认（2026-09）：群聊豁免好友检查。</b>
 * 若将来产品上改为群内也校验好友，只改 {@link #evaluate} 里一处即可——
 * 但要注意那意味着每条群消息都要对全体成员做好友查询，需先设计缓存方案。
 *
 * <p><b>为什么单独抽成一个类而不是写在 Service 里</b>：这条规则有 4 个输入维度
 * （会话类型 × 是否成员 × 好友状态 × 是否被拉黑），组合起来十来种情况。
 * 写进业务方法里，就只能靠「跑一遍看看」验证；抽成纯函数后，
 * {@code MessageSendPolicyTest} 可以穷举全部组合，一条条钉死。
 * 错误码回归是最难发现的一类回归——接口结构没变、数据也对，
 * 只是本该 40003 的地方返回了 200。
 */
public final class MessageSendPolicy {

    private MessageSendPolicy() {
    }

    /**
     * 判定是否放行。
     *
     * @param convType   会话类型
     * @param isMember   发送者是否为该会话成员
     * @param friendship 发送者与对方（单聊的另一方）的好友状态；无任何关系时为 {@code null}。
     *                   群聊场景忽略此参数。
     * @return 拒绝原因；放行时返回 {@code null}
     */
    public static ErrorCode evaluate(ConvType convType, boolean isMember, FriendshipStatus friendship) {
        if (convType == null) {
            throw new IllegalArgumentException("convType 不能为 null：无法判定该走单聊还是群聊规则");
        }

        // 第一道：成员身份。群聊和单聊都要过这一关。
        if (!isMember) {
            return ErrorCode.NOT_A_MEMBER;
        }
        if (convType == ConvType.GROUP) {
            return null;
        }

        // 第二道：单聊的好友关系
        if (friendship == null) {
            return ErrorCode.NOT_FRIENDS;
        }
        return switch (friendship) {
            case ACCEPTED -> null;
            // 拉黑是「关系存在但被拒」，语义上比「不是好友」更明确：
            // 客户端应停止重试而不是引导用户去加好友（那样会被对方直接否决）
            case BLOCKED -> ErrorCode.BLOCKED_BY_PEER;
            // PENDING 仍未成为好友，与无关系同样处理
            case PENDING -> ErrorCode.NOT_FRIENDS;
        };
    }

    /**
     * 与 {@link #evaluate} 同规则，但不放行时直接抛异常。
     *
     * <p>REST 与长连接两条链路都调它，从而保证「同一个非法请求，
     * 走 HTTP 和走 WebSocket 得到同一个错误码」——这正是「对等」在错误处理上的体现。
     */
    public static void check(ConvType convType, boolean isMember, FriendshipStatus friendship) {
        ErrorCode rejection = evaluate(convType, isMember, friendship);
        if (rejection != null) {
            throw new TmException(rejection,
                    "convType=" + convType + ",isMember=" + isMember + ",friendship=" + friendship);
        }
    }
}
