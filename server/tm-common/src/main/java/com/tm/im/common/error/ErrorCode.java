package com.tm.im.common.error;

/**
 * 全平台错误码。
 *
 * <p><b>本枚举是 {@code docs/integration/07-errors-limits.md} §2 错误码表的代码投影。</b>
 * 两者由 {@code tools/verify_error_codes.py} 做双向一致性校验：文档里的每一行都必须
 * 在此存在，此处的每一项也必须在文档里有对应行。<b>改一处不同步改另一处，自检会失败。</b>
 * 这样做的原因是：错误码是对外契约，Agent 开发者照着文档写代码；一旦代码里的
 * 文案与文档不一致，联调时表现为「文档说 40003 是非好友，实际返回了别的」，
 * 而排查方向会被误导到业务逻辑上，而不是文案漂移上。
 *
 * <p>约定（见文档 §1）：
 * <ul>
 *   <li>业务响应恒为 HTTP 200，靠 {@code code} 区分成败；</li>
 *   <li>只有认证 / 限流 / 服务端异常才用非 200 状态码（{@link #httpStatus()}）；</li>
 *   <li>{@code 4xxxx} 客户端错误，不可盲目重试；{@code 5xxxx} 服务端错误，可退避重试。</li>
 * </ul>
 */
public enum ErrorCode {

    // ======================= 通用 40000-40099 =======================
    OK(0, "ok", false),
    BAD_REQUEST(40000, "bad request", false),
    MISSING_PARAMETER(40001, "missing parameter", false),
    INVALID_PARAMETER(40002, "invalid parameter", false),
    /** ★ 产品硬规则：非好友不能发消息（DESIGN §11.6）。单聊专属，群聊不走这条。 */
    NOT_FRIENDS(40003, "not friends", false),
    INVALID_HANDLE(40004, "invalid handle", false),
    HANDLE_EXISTS(40005, "handle exists", false),
    CONTENT_TOO_LONG(40006, "content too long", false),
    INVALID_MSG_TYPE(40007, "invalid msg_type", false),
    MEDIA_NOT_FOUND(40008, "media not found", false),
    CONTENT_TYPE_MISMATCH(40009, "content type mismatch", false),
    INVALID_CURSOR(40010, "invalid cursor", false),
    REPLY_TO_NOT_FOUND(40011, "reply_to not found", false),
    MESSAGE_TOO_LARGE(40012, "message too large", false),
    UNSUPPORTED_IMAGE_TYPE(40013, "unsupported image type", false),
    IMAGE_TOO_LARGE(40014, "image too large", false),

    // =================== 认证与权限 40100-40399 ===================
    UNAUTHORIZED(40101, "unauthorized", false),
    INVALID_TOKEN_FORMAT(40102, "invalid token format", false),
    /** 文档标注可重试：刷新 token 后重试。 */
    TOKEN_EXPIRED(40103, "token expired", true),
    INVALID_REFRESH_TOKEN(40104, "invalid refresh token", false),
    INVALID_API_KEY(40105, "invalid api key", false),
    API_KEY_REVOKED(40106, "api key revoked", false),
    ACCOUNT_SUSPENDED(40301, "account suspended", false),
    PERMISSION_DENIED(40302, "permission denied", false),
    NOT_A_MEMBER(40303, "not a member", false),
    BLOCKED_BY_PEER(40304, "blocked by peer", false),
    NO_PRIVILEGE(40305, "no privilege", false),
    OWNER_CANNOT_LEAVE(40306, "owner cannot leave", false),

    // ======================= 资源 40400-40999 =======================
    NOT_FOUND(40400, "not found", false),
    ACTOR_NOT_FOUND(40401, "actor not found", false),
    CONVERSATION_NOT_FOUND(40402, "conversation not found", false),
    MESSAGE_NOT_FOUND(40403, "message not found", false),
    POST_NOT_FOUND(40404, "post not found", false),
    ALREADY_FRIENDS(40901, "already friends", false),
    REQUEST_PENDING(40902, "request pending", false),
    BLOCKED(40903, "blocked", false),
    SELF_OPERATION(40904, "self operation", false),
    /**
     * 已经是群成员。
     *
     * <p><b>§4.9 的「加人」刻意不返回它</b>：那个接口收的是一<b>批</b>人，其中几个已在群里
     * 不该让另外几个也加不进去（部分成功不能报错），而「一个都没加进去」也不是失败
     * （重复调用与调用一次等价，与 {@code openDirect} 的 {@code created=false} 同一取舍）。
     * 真正的做法是把被跳过的人在响应里列出来（{@code already_members}），客户端据此刷新列表。
     * 于是本码在 §4.9 里没有使用处——留着是因为它是错误码总表的一部分。
     */
    ALREADY_MEMBER(40905, "already member", false),
    GROUP_MEMBER_LIMIT(40906, "group member limit", false),
    ALREADY_LIKED(40907, "already liked", false),
    /**
     * 「目标不是这个会话的成员」——只用于 §4.9 里<b>以别人为对象</b>的接口（踢人、改角色）。
     *
     * <p>为什么不复用 {@link #NOT_A_MEMBER}：那个码在每一个会话接口上都可能出现，
     * 客户端的处理是「我已经不在会话里了」——关掉页面、把它从会话列表里删掉。
     * 而「目标（另一个人）不在群里」该做的动作完全不同：刷新成员列表，页面照旧。
     * 共用一个码会让一次「成员列表过期」被当成「我失去了这个会话」，
     * 而那个误判是<b>破坏性</b>的（客户端会删掉本地会话）。
     */
    TARGET_NOT_MEMBER(40908, "target not a member", false),

    // ======================= 限流 42900-42999 =======================
    RATE_LIMIT_EXCEEDED(42901, "rate limit exceeded", true),
    /** 日配额类均为「次日可重试」，语义上仍算 retryable。 */
    DAILY_QUOTA_EXCEEDED(42902, "daily quota exceeded", true),
    FRIEND_REQUEST_QUOTA_EXCEEDED(42903, "friend request quota exceeded", true),
    CONNECTION_LIMIT_EXCEEDED(42904, "connection limit exceeded", true),
    UPLOAD_QUOTA_EXCEEDED(42905, "upload quota exceeded", true),

    // ======================= 服务端 50000+ =======================
    INTERNAL_ERROR(50000, "internal error", true),
    DATABASE_UNAVAILABLE(50001, "database unavailable", true),
    CACHE_UNAVAILABLE(50002, "cache unavailable", true),
    STORAGE_UNAVAILABLE(50003, "storage unavailable", true),
    SERVICE_OVERLOADED(50004, "service overloaded", true),
    WEBHOOK_DELIVERY_FAILED(50005, "webhook delivery failed", true),
    REQUEST_TIMEOUT(50006, "request timeout", true);

    private final int code;
    private final String message;
    private final boolean retryable;

    ErrorCode(int code, String message, boolean retryable) {
        this.code = code;
        this.message = message;
        this.retryable = retryable;
    }

    public int code() {
        return code;
    }

    /** 对外文案。英文小写、无句点——与文档表格逐字一致，勿改。 */
    public String message() {
        return message;
    }

    public boolean retryable() {
        return retryable;
    }

    /**
     * 该错误对应的 HTTP 状态码。
     *
     * <p>刻意由 {@code code} 推导而非逐个常量硬写：错误码分段本身就是分类，
     * 推导能保证「新增一个 5xxxx 错误码」不会忘记配 500。
     * 例外只有 {@link #SERVICE_OVERLOADED} → 503（文档 §1 表格明确列出）。
     */
    public int httpStatus() {
        if (code == 0) {
            return 200;
        }
        if (code >= 40101 && code <= 40106) {
            return 401;
        }
        if (code >= 40301 && code <= 40306) {
            return 403;
        }
        if (code >= 42901 && code <= 42999) {
            return 429;
        }
        if (code == 50004) {
            return 503;
        }
        if (code >= 50000) {
            return 500;
        }
        // 其余（含 40400-40908 资源类）都是「业务失败但 HTTP 200」。
        // 注意 404 状态码在本系统专指「路由不存在」，不用于「资源不存在」。
        return 200;
    }

    public boolean isServerError() {
        return code >= 50000;
    }

    public boolean isClientError() {
        return code >= 40000 && code < 50000;
    }

    /** 按数字码反查。未知码返回 {@code null}，调用方需显式处理。 */
    public static ErrorCode of(int code) {
        for (ErrorCode e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }
}
