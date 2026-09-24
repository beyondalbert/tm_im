package com.tm.im.common.api;

import com.tm.im.common.error.ErrorCode;

/**
 * 统一响应信封 —— 与 {@code docs/integration/07-errors-limits.md} §1 的约定一致：
 *
 * <pre>
 * { "code": 0,     "message": "ok",    "data": { ... } }   成功
 * { "code": 40003, "message": "not friends", "data": null } 业务失败
 * </pre>
 *
 * <p><b>业务失败也是 HTTP 200</b>，靠 {@code code} 区分。这不是随意选择：
 * 若用 HTTP 状态码承载业务语义，反向代理、网关、重试中间件会自作主张地
 * 对 4xx/5xx 做处理（重试、熔断、改写），而「非好友」这种业务失败被重试
 * 一万次也不会成功，只会放大无效流量。
 *
 * <p>用 record 而非普通类：这是纯数据载体，不需要可变性与继承。
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.OK.code(), ErrorCode.OK.message(), data);
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, T data) {
        return new ApiResponse<>(errorCode.code(), errorCode.message(), data);
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode) {
        return fail(errorCode, null);
    }

    public boolean isOk() {
        return code == ErrorCode.OK.code();
    }
}
