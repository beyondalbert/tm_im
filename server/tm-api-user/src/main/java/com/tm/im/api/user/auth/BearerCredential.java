package com.tm.im.api.user.auth;

import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;

/**
 * 从 {@code Authorization} 头里取出凭证（03-rest-api.md §1.2）。
 *
 * <p><b>为什么单独一个类</b>：人类用 JWT、Agent 用 api_key，但请求头格式
 * <b>完全一样</b>（都是 {@code Bearer}，02-auth.md §1 的「注意」）。判断凭证种类
 * 是 {@code IdentityService} 的事（看 {@code sk_} 前缀）。这里只负责
 * 「头有没有、前缀对不对」，把这两种完全不同的失败拆成两个错误码：
 *
 * <table border="1">
 *   <tr><th>情况</th><th>码</th><th>客户端该改什么</th></tr>
 *   <tr><td>没有 Authorization 头</td><td>40101</td><td>加上请求头</td></tr>
 *   <tr><td>有头但不是 Bearer 形式</td><td>40102</td><td>检查 {@code Bearer } 前缀</td></tr>
 * </table>
 *
 * <p>合成一个「401 未授权」会让排查方向指向「我登录了吗」，而实际要改的是
 * 「我的 HTTP 客户端没把请求头带过来」——这是两件在代码里相隔很远的事。
 *
 * <p><b>大小写</b>：RFC 7235 里 scheme 是大小写不敏感的，所以 {@code bearer} /
 * {@code BEARER} 都接受。而 {@code Bearer} 之后的凭证<b>不做任何</b> trim —— 前后
 * 空格是凭证的一部分（base64url 与 {@code sk_} 串都不会含空格，所以一个带空格的
 * 凭证必然是人手拼错了；把它 trim 掉只是让拼错的那个人更难发现）。
 */
public final class BearerCredential {

    /** RFC 7235 的 scheme 名。改动它等于改对外协议，需同步 03-rest-api.md §1.2。 */
    public static final String SCHEME = "Bearer";

    /** 请求头名。 */
    public static final String HEADER = "Authorization";

    private static final String PREFIX = SCHEME + " ";

    private BearerCredential() {
    }

    /**
     * @param headerValue 原始请求头值，可为 null
     * @return 凭证明文（未做 trim，见类注释）
     * @throws TmException 40101（缺失）或 40102（不是 Bearer 形式 / 凭证为空）
     */
    public static String require(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new TmException(ErrorCode.UNAUTHORIZED, "缺少 " + HEADER + " 头");
        }
        if (headerValue.length() < PREFIX.length()
                || !headerValue.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            // 只回「格式错误」，不回显实际收到的值：那个值可能是一把正在被
            // 误用的真凭证，回显等于把它写进客户端日志与代理日志。
            throw new TmException(ErrorCode.INVALID_TOKEN_FORMAT,
                    "不是 `Bearer <凭证>` 形式");
        }
        String credential = headerValue.substring(PREFIX.length());
        if (credential.isEmpty()) {
            throw new TmException(ErrorCode.INVALID_TOKEN_FORMAT, "Bearer 之后为空");
        }
        return credential;
    }
}
