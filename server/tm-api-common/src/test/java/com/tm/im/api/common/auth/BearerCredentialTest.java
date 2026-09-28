package com.tm.im.api.common.auth;

import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code Authorization} 头的解析规则（03-rest-api.md §1.2 / 02-auth.md §4）。
 *
 * <p>这里只测「头怎么拆」。凭证本身有效性的判定在 {@code IdentityService} 里
 * （看 {@code sk_} 前缀分派），因此本类不碰任何服务。
 */
class BearerCredentialTest {

    @Test
    @DisplayName("正常取值：Bearer 之后的内容原样返回")
    void extractsCredential() {
        assertThat(BearerCredential.require("Bearer eyJhbGciOi.HS256.x")).isEqualTo("eyJhbGciOi.HS256.x");
        assertThat(BearerCredential.require("Bearer sk_live_9f2c1d7a")).isEqualTo("sk_live_9f2c1d7a");
    }

    @Test
    @DisplayName("scheme 大小写不敏感（RFC 7235），但大小写混写也算合法")
    void schemeIsCaseInsensitive() {
        assertThat(BearerCredential.require("bearer abc")).isEqualTo("abc");
        assertThat(BearerCredential.require("BEARER abc")).isEqualTo("abc");
        assertThat(BearerCredential.require("BeArEr abc")).isEqualTo("abc");
    }

    @Test
    @DisplayName("凭证前后空格不被 trim：那是拼错了，不是可忽略的噪声")
    void doesNotTrimCredential() {
        // trim 掉之后，一个「多打了一个空格」的凭证会变成有效凭证并登录成功，
        // 而当事人永远不知道自己的客户端拼错了——直到他去别处手工拼一次。
        assertThat(BearerCredential.require("Bearer  abc")).isEqualTo(" abc");
        assertThat(BearerCredential.require("Bearer abc ")).isEqualTo("abc ");
    }

    @Test
    @DisplayName("缺失 / 空白 → 40101（客户端该去加请求头）")
    void missingHeaderIsUnauthorized() {
        for (String header : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> BearerCredential.require(header))
                    .isInstanceOf(TmException.class)
                    .extracting(e -> ((TmException) e).errorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED);
        }
    }

    @ParameterizedTest(name = "Authorization={0} → 40102")
    @ValueSource(strings = {"abc", "Basic dXNlcjpwYXNz", "Bearer", "BearerXyz", "Token abc"})
    @DisplayName("不是 Bearer 形式 → 40102（客户端该改的是拼装代码）")
    void malformedHeaderIsInvalidTokenFormat(String header) {
        assertThatThrownBy(() -> BearerCredential.require(header))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN_FORMAT);
    }

    @Test
    @DisplayName("Bearer 之后为空 → 40102（而不是当作 40101）")
    void emptyCredentialIsInvalidFormat() {
        assertThatThrownBy(() -> BearerCredential.require("Bearer "))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN_FORMAT);
    }

    @Test
    @DisplayName("错误信息里不回显收到的凭证（它可能是一把真钥匙）")
    void errorDetailNeverEchoesCredential() {
        TmException e = (TmException) org.assertj.core.api.Assertions.catchThrowable(
                () -> BearerCredential.require("Basic c2VjcmV0OnBhc3N3b3Jk"));

        assertThat(e.getMessage()).doesNotContain("c2VjcmV0OnBhc3N3b3Jk");
        assertThat(e.detail()).doesNotContain("c2VjcmV0OnBhc3N3b3Jk");
    }
}
