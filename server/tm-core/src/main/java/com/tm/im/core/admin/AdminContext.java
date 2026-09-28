package com.tm.im.core.admin;

import com.tm.im.domain.entity.AdminUser;
import com.tm.im.domain.enums.AdminRole;

/**
 * 已认证的后台身份 —— 请求处理期间「我是谁」的唯一载体。
 *
 * <p>它在核心层的地位与用户端的 {@code AuthContext} 对应，但<b>刻意是两个类型</b>：
 * 两者都只有 id 与名字，看起来可以合并成一个「Principal」——而那正是危险的开始。
 * 用户上下文里带着 {@code actorType}（人/Agent），后台上下文里带着 {@code role}；
 * 一旦共用一个类型，某天就会有人在用户端接口里读到它并据此放行，
 * 而「后台身份能调用户端接口」这件事本身（虽然它确实是同一个 actor 模型的反面）
 * 会在代码里表现为一次类型共用，而不是一次明示的决策。
 *
 * <p>{@code role} 每次从库里读（不放进 token）：权限变更必须立刻生效，
 * 而会话可能还有 8 小时才过期。这与用户端「角色每次由服务端查库」是同一条理由。
 *
 * <p>{@code clientIp} 放在身份里而不是每次调用时当参数传：审计行必须带上它
 * （"谁在什么时候从哪改了什么"），而让它成为一个可选参数的结果必然是
 * 「大多数调用点忘了传」。它与身份是同一批事实：请求头里带过来的东西，
 * 在处理这个请求的整条路径上不变。
 */
public record AdminContext(long adminId, String username, String displayName, AdminRole role,
                           String clientIp) {

    public static AdminContext of(AdminUser admin, String clientIp) {
        return new AdminContext(admin.getId(), admin.getUsername(), admin.getDisplayName(),
                admin.getRole(), clientIp);
    }

    /** 是不是超级管理员（能建号、能停用别的后台账号）。 */
    public boolean isSuper() {
        return role == AdminRole.SUPER;
    }
}
