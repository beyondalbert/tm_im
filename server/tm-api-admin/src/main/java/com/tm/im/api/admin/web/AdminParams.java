package com.tm.im.api.admin.web;

import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.domain.enums.ActorStatus;
import com.tm.im.domain.enums.ActorType;
import com.tm.im.domain.enums.AdminRole;
import com.tm.im.domain.enums.AdminStatus;

/**
 * 查询参数与请求体里的「数字码 → 枚举」转换。
 *
 * <p>集中在这里而不是散在控制器里：后台的筛选参数有一半是码值，
 * 而「把 3 当成 MEMBER 传进来」这类越界输入如果各写各的，
 * 总有一个接口会静默地用 {@code null} 表示「不过滤」——于是客户端以为
 * 自己筛了 AGENT，实际拿到的是全部用户。所以越界一律 {@code 40002}。
 *
 * <p>刻意<b>不接受字符串</b>（{@code "AGENT"}）：用户端有一条历史包袱
 * （§4.9 的 role 曾接受过名字），后台没有存量客户端，
 * 于是这里保持「一种写法」，少一类只在一侧成立的行为。
 */
public final class AdminParams {

    private AdminParams() {
    }

    public static ActorType actorType(Integer code) {
        if (code == null) {
            return null;
        }
        ActorType type = ActorType.of(code);
        if (type == null) {
            throw invalid("actor_type", code, "1=HUMAN 2=AGENT");
        }
        return type;
    }

    public static ActorStatus actorStatus(Integer code) {
        if (code == null) {
            return null;
        }
        ActorStatus status = ActorStatus.of(code);
        if (status == null) {
            throw invalid("status", code, "1=ACTIVE 2=SUSPENDED");
        }
        return status;
    }

    /** 改参与者状态时目标必须是显式的：缺一个字段就静默启用一个被封的号太危险。 */
    public static ActorStatus requiredActorStatus(Integer code) {
        if (code == null) {
            throw new TmException(ErrorCode.MISSING_PARAMETER, "缺少 status（1=ACTIVE 2=SUSPENDED）");
        }
        return actorStatus(code);
    }

    public static AdminStatus adminStatus(Integer code) {
        if (code == null) {
            throw new TmException(ErrorCode.MISSING_PARAMETER, "缺少 status（1=ACTIVE 2=DISABLED）");
        }
        AdminStatus status = AdminStatus.of(code);
        if (status == null) {
            throw invalid("status", code, "1=ACTIVE 2=DISABLED");
        }
        return status;
    }

    public static AdminRole adminRole(Integer code) {
        if (code == null) {
            return null;
        }
        AdminRole role = AdminRole.of(code);
        if (role == null) {
            throw invalid("role", code, "1=SUPER 2=OPS");
        }
        return role;
    }

    private static TmException invalid(String field, Integer code, String expected) {
        return new TmException(ErrorCode.INVALID_PARAMETER,
                field + "=" + code + " 不是合法取值（" + expected + "）");
    }
}
