package com.tm.im.common.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码枚举的自洽性测试。
 *
 * <p>这里的断言分两类：
 * <ol>
 *   <li><b>内部自洽</b>：码值不重复、文案非空、分段与 HTTP 状态映射一致；</li>
 *   <li><b>契约一致性</b>：与 {@code docs/integration/07-errors-limits.md} 的对照
 *       由 {@code tools/verify_error_codes.py} 承担（它需要读 Markdown，用 Python 更自然）。</li>
 * </ol>
 * 两者互补：Java 侧管「枚举自身没写坏」，Python 侧管「枚举和文档说的是同一件事」。
 */
class ErrorCodeTest {

    /** 文档规定的文案风格：小写英文、允许空格与下划线、可带数字，无句点。 */
    private static final Pattern MESSAGE_STYLE = Pattern.compile("[a-z0-9_ ]+");

    @Test
    @DisplayName("码值全局唯一（重复会让客户端无法区分错误）")
    void codesAreUnique() {
        Set<Integer> seen = new HashSet<>();
        for (ErrorCode e : ErrorCode.values()) {
            assertThat(seen.add(e.code()))
                    .as("错误码 %d (%s) 与已有项重复", e.code(), e.name())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("对外文案非空、风格统一、且不重复")
    void messagesAreWellFormed() {
        Set<String> seen = new HashSet<>();
        for (ErrorCode e : ErrorCode.values()) {
            assertThat(e.message()).as("%s 的文案不能为空", e.name()).isNotBlank();
            assertThat(MESSAGE_STYLE.matcher(e.message()).matches())
                    .as("%s 的文案 '%s' 应为小写英文；文档里的表格就是按这个格式写的", e.name(), e.message())
                    .isTrue();
            assertThat(seen.add(e.message()))
                    .as("文案 '%s' (%s) 重复——客户端只能靠文案提示用户，重复文案等于没提示",
                            e.message(), e.name())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("HTTP 状态码映射：只有认证/限流/服务端异常才非 200")
    void httpStatusMapping() {
        assertThat(ErrorCode.OK.httpStatus()).isEqualTo(200);

        // 业务类失败一律 200，包括资源不存在——404 在本系统专指「路由不存在」
        List<ErrorCode> businessFailures = List.of(
                ErrorCode.BAD_REQUEST, ErrorCode.NOT_FRIENDS, ErrorCode.ALREADY_FRIENDS,
                ErrorCode.NOT_FOUND, ErrorCode.ACTOR_NOT_FOUND, ErrorCode.CONVERSATION_NOT_FOUND);
        for (ErrorCode e : businessFailures) {
            assertThat(e.httpStatus())
                    .as("%s 属于业务失败，应返回 HTTP 200 + code，否则网关会自行重试/熔断", e.name())
                    .isEqualTo(200);
        }

        for (ErrorCode e : ErrorCode.values()) {
            int expected = switch (segmentOf(e.code())) {
                case AUTH -> 401;
                case FORBIDDEN -> 403;
                case RATE_LIMIT -> 429;
                case SERVER -> e == ErrorCode.SERVICE_OVERLOADED ? 503 : 500;
                default -> 200;
            };
            assertThat(e.httpStatus()).as("%s(%d)", e.name(), e.code()).isEqualTo(expected);
        }
    }

    private enum Segment { OK, BUSINESS, AUTH, FORBIDDEN, RESOURCE, RATE_LIMIT, SERVER }

    private static Segment segmentOf(int code) {
        if (code == 0) {
            return Segment.OK;
        }
        if (code >= 40000 && code <= 40099) {
            return Segment.BUSINESS;
        }
        if (code >= 40100 && code <= 40399) {
            return code < 40300 ? Segment.AUTH : Segment.FORBIDDEN;
        }
        if (code >= 40400 && code <= 40999) {
            return Segment.RESOURCE;
        }
        if (code >= 42900 && code <= 42999) {
            return Segment.RATE_LIMIT;
        }
        if (code >= 50000) {
            return Segment.SERVER;
        }
        throw new AssertionError("错误码 " + code + " 落在未定义的分段里");
    }

    @Test
    @DisplayName("分段归属：4xxxx 客户端错误、5xxxx 服务端错误")
    void errorClassClassification() {
        for (ErrorCode e : ErrorCode.values()) {
            if (e == ErrorCode.OK) {
                assertThat(e.isClientError()).isFalse();
                assertThat(e.isServerError()).isFalse();
                continue;
            }
            assertThat(e.isClientError() ^ e.isServerError())
                    .as("%s(%d) 必须且只能属于客户端或服务端错误之一", e.name(), e.code())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("非好友发消息 = 40003 且不可重试（重试一万次也不会变成好友）")
    void notFriendsIsPermanent() {
        assertThat(ErrorCode.NOT_FRIENDS.code()).isEqualTo(40003);
        assertThat(ErrorCode.NOT_FRIENDS.message()).isEqualTo("not friends");
        assertThat(ErrorCode.NOT_FRIENDS.retryable()).isFalse();
    }

    @Test
    @DisplayName("可重试标记与文档一致：仅 40103 与全部 429xx、5xxxx 标记可重试")
    void retryableFlagsMatchDocumentedTable() {
        Set<ErrorCode> retryable = Arrays.stream(ErrorCode.values())
                .filter(ErrorCode::retryable)
                .collect(Collectors.toSet());

        assertThat(retryable).as("文档中标注 ✅ 的项").containsExactlyInAnyOrder(
                ErrorCode.TOKEN_EXPIRED,
                ErrorCode.RATE_LIMIT_EXCEEDED,
                ErrorCode.DAILY_QUOTA_EXCEEDED,
                ErrorCode.FRIEND_REQUEST_QUOTA_EXCEEDED,
                ErrorCode.CONNECTION_LIMIT_EXCEEDED,
                ErrorCode.UPLOAD_QUOTA_EXCEEDED,
                ErrorCode.INTERNAL_ERROR,
                ErrorCode.DATABASE_UNAVAILABLE,
                ErrorCode.CACHE_UNAVAILABLE,
                ErrorCode.STORAGE_UNAVAILABLE,
                ErrorCode.SERVICE_OVERLOADED,
                ErrorCode.WEBHOOK_DELIVERY_FAILED,
                ErrorCode.REQUEST_TIMEOUT);
    }

    @Test
    @DisplayName("of() 对全部已定义码可反查，对未知码返回 null")
    void reverseLookup() {
        for (ErrorCode e : ErrorCode.values()) {
            assertThat(ErrorCode.of(e.code())).isSameAs(e);
        }
        assertThat(ErrorCode.of(12345)).isNull();
        assertThat(ErrorCode.of(-1)).isNull();
    }

    @Test
    @DisplayName("文案是英文小写——直接回给客户端，不要夹带中文或调试信息")
    void messagesAreClientSafe() {
        for (ErrorCode e : ErrorCode.values()) {
            assertThat(e.message())
                    .as("%s 的文案不应包含中文", e.name())
                    .doesNotContainPattern("[\\u4e00-\\u9fff]")
                    .as("%s 的文案不应以句点结尾", e.name())
                    .doesNotEndWith(".");
        }
    }
}
