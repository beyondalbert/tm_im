package com.tm.im.api.user.web;

import com.tm.im.common.api.ApiResponse;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.function.Function;

/**
 * REST 侧的统一异常翻译（03-rest-api.md §1.3 / §1.4）。
 *
 * <p>契约只有一句：<b>响应体永远是 {@link ApiResponse}，失败用 {@code code} 表达</b>。
 * 之所以不能靠 HTTP 状态码承载业务语义：反向代理、网关、重试中间件会自作主张地
 * 对 4xx/5xx 做重试/熔断/改写，而「非好友」这种业务失败重试一万次也不会成功。
 *
 * <p><b>HTTP 状态码用在哪</b>（与 §1.4 的表逐条对应）：
 * <ul>
 *   <li>{@code TmException} 自己带 {@code httpStatus()}——401 类走 401、403 类走 403、
 *       5xxxx 走 500/503。它可以这么做的原因是「错误码分段本身就是分类」，
 *       所以不存在两处口径漂移的问题。</li>
 *   <li>路由不存在 / 方法不支持走 404 / 405，<b>并且带上 ApiResponse 体</b>。
 *       Spring 默认会返回自己的错误 JSON（{@code {"timestamp":...,"error":"Not Found"}}），
 *       于是客户端要写两套解析逻辑——而其中一套只在「把 URL 拼错」时才走到，
 *       最不容易被发现。</li>
 * </ul>
 *
 * <p><b>为什么未捕获异常只回 50000 不带 message</b>：异常消息里常有 SQL 片段、
 * 表名、甚至参数值。它们在日志里有价值，在响应体里是信息泄漏。
 * 客户端的正确做法是「拿 trace id 找服务端」，而不是「自己解析 message」。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * 业务异常。这里刻意<b>不</b>把 {@code detail} 放进响应体：它是给日志用的
     * （{@code "handle=alice"}、{@code "from=1,to=2"}），对外只给错误码表里的文案。
     * 把 detail 透出去会让排查依赖具体文案，而文案是内部实现细节，改一次就断一次。
     */
    @ExceptionHandler(TmException.class)
    public ResponseEntity<ApiResponse<Void>> handleTmException(TmException e) {
        if (e.errorCode().isServerError()) {
            // 5xxxx 的 TmException 照常采集堆栈（见 TmException 的类注释），所以这里有东西可打
            log.error("业务异常（服务端）code={} detail={}", e.code(), e.detail(), e);
        } else {
            log.debug("业务异常（客户端）code={} detail={}", e.code(), e.detail());
        }
        return ResponseEntity.status(e.httpStatus()).body(ApiResponse.fail(e.errorCode()));
    }

    /**
     * 请求体不是合法 JSON（或字段类型对不上）。
     *
     * <p>HTTP 200 + 40000：这是<b>业务失败</b>（客户端发的 JSON 结构错了），
     * 而不是「请求没能到达应用」。用 400 会让「HTTP 客户端自动重试 4xx」这类
     * 配置把一段永远不可能成功的请求反复打过来。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        log.debug("请求体无法解析: {}", e.getMessage());
        return ok(ErrorCode.BAD_REQUEST);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class})
    public ResponseEntity<ApiResponse<Void>> handleMissing(Exception e) {
        log.debug("缺少参数: {}", e.getMessage());
        return ok(ErrorCode.MISSING_PARAMETER);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.debug("参数类型不符: {}", e.getMessage());
        return ok(ErrorCode.INVALID_PARAMETER);
    }

    /**
     * 路由不存在 → HTTP 404 + code 40400。
     *
     * <p>这里 HTTP 状态码与 {@link ErrorCode#httpStatus()} 不一致（后者把 40400 归为 200），
     * 是有意的，也与 §1.4 的表一致：「404 专指路由不存在」，
     * 而「资源不存在」走 {@code 200 + code=40400}。
     * 两者共用同一个码，是因为对客户端而言要做的动作相同（别重试、去检查 ID），
     * 差别只在「谁写错了 URL」这个服务端视角。
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception e) {
        log.debug("路由不存在: {}", requestPath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException e) {
        log.debug("方法不支持: {} {}", e.getMethod(), requestPath());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.fail(ErrorCode.BAD_REQUEST));
    }

    /**
     * 兜底。走到这里说明是服务端缺陷（或依赖故障没被翻译成 TmException），
     * 因此必须是 error 级日志 + 500。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("未预期的异常 {} {}", requestPath(), requestMethod(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR));
    }

    /** 「业务失败但 HTTP 200」的唯一出口（见 §1.4）。 */
    private static ResponseEntity<ApiResponse<Void>> ok(ErrorCode code) {
        return ResponseEntity.ok(ApiResponse.fail(code));
    }

    /**
     * 请求路径（与方法），只用于日志。
     *
     * <p>不直接拿 {@code HttpServletRequest} 而经 {@code RequestContextHolder}：
     * 兜底异常处理器可能在任何阶段被调用，那时请求属性可能已被清理。
     * 这里失败只影响一条日志，所以返回占位符而不是让处理器再抛一次——
     * <b>异常处理器自己抛异常</b>是最难排查的一类问题，
     * 客户端会收到一个与真实错误无关的 500。
     */
    private static String requestPath() {
        return fromRequest(HttpServletRequest::getRequestURI);
    }

    private static String requestMethod() {
        return fromRequest(HttpServletRequest::getMethod);
    }

    private static String fromRequest(Function<HttpServletRequest, String> extract) {
        try {
            if (!(RequestContextHolder.getRequestAttributes()
                    instanceof ServletRequestAttributes servlet)) {
                return "<unknown>";
            }
            return extract.apply(servlet.getRequest());
        } catch (RuntimeException e) {
            return "<unknown>";
        }
    }
}
