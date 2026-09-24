package com.tm.im.common.error;

/**
 * 业务异常：携带 {@link ErrorCode}，由 API 层的统一异常处理器翻译成响应体。
 *
 * <p><b>一处刻意的性能设计</b>：4xxxx 类业务异常<b>不采集堆栈</b>。
 * 理由是这类异常是「预期内的控制流」——非好友发消息、token 过期、限流命中，
 * 在正常运行的系统中每秒会发生成千上万次。采集堆栈的成本（填充 + 序列化）
 * 在高并发下会变成可观的开销，而它提供的信息量为零：出问题的位置就是
 * 抛出它的那一行，异常码本身已经说明了一切。
 *
 * <p>5xxxx 类隐藏的是服务端缺陷，堆栈是排查的唯一线索，因此照常采集。
 *
 * <p>这个取舍是可验证的：{@code TmExceptionTest} 明确断言两类异常的
 * {@code getStackTrace()} 长度差异，避免以后有人「顺手」把它改回去。
 */
public class TmException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    /** 附加的业务上下文（如 "from=1,to=2"），仅用于日志，不对外返回。 */
    private final transient String detail;

    public TmException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public TmException(ErrorCode errorCode, String detail) {
        // 参数顺序：message, cause, enableSuppression, writableStackTrace
        //   enableSuppression 恒为 true（默认行为，没理由关掉）；
        //   writableStackTrace 只在服务端异常时开——见类注释的性能取舍。
        super(buildMessage(errorCode, detail), null, true, errorCode.isServerError());
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public TmException(ErrorCode errorCode, String detail, Throwable cause) {
        super(buildMessage(errorCode, detail), cause, true, true);
        this.errorCode = errorCode;
        this.detail = detail;
    }

    private static String buildMessage(ErrorCode errorCode, String detail) {
        return detail == null || detail.isBlank()
                ? "[" + errorCode.code() + "] " + errorCode.message()
                : "[" + errorCode.code() + "] " + errorCode.message() + " (" + detail + ")";
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public int code() {
        return errorCode.code();
    }

    public int httpStatus() {
        return errorCode.httpStatus();
    }

    public boolean retryable() {
        return errorCode.retryable();
    }

    public String detail() {
        return detail;
    }

    /** 为「非好友不能发消息」这一硬规则提供语义化构造，避免各处手写 40003。 */
    public static TmException notFriends(long fromActor, long toActor) {
        return new TmException(ErrorCode.NOT_FRIENDS, "from=" + fromActor + ",to=" + toActor);
    }

    public static TmException param(String detail) {
        return new TmException(ErrorCode.INVALID_PARAMETER, detail);
    }

    public static TmException notFound(ErrorCode which, String detail) {
        return new TmException(which, detail);
    }
}
