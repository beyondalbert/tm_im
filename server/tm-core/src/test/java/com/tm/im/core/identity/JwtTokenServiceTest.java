package com.tm.im.core.identity;

import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.domain.enums.ActorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * JWT 签发/校验的边界与安全属性。
 *
 * <p>时钟是注入的固定时钟：{@code exp} 这类判断如果用真实时间，
 * 测试只能靠 {@code sleep} 逼近边界，进而变成偶发失败。
 */
class JwtTokenServiceTest {

    private static final String SECRET = "test-secret-0123456789abcdef0123456789abcdef";
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static JwtTokenService at(Instant now) {
        return new JwtTokenService(SECRET, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("签发→校验往返：角色、handle、有效期都还原正确")
    void roundTrip() {
        JwtTokenService svc = at(T0);
        String token = svc.issue(730000000000000001L, "alice", ActorType.HUMAN, Duration.ofHours(2));

        TokenClaims claims = at(T0.plusSeconds(60)).verify(token);
        assertThat(claims.actorId()).isEqualTo(730000000000000001L);
        assertThat(claims.handle()).isEqualTo("alice");
        assertThat(claims.actorType()).isEqualTo(ActorType.HUMAN);
        assertThat(claims.issuedAt()).isEqualTo(T0);
        assertThat(claims.expiresAt()).isEqualTo(T0.plusSeconds(7200));
    }

    @Test
    @DisplayName("token 结构符合 JWS 三段式，且签名可被独立实现复算")
    void structureIsSpecCompliant() {
        JwtTokenService svc = at(T0);
        String token = svc.issue(42L, "bob", ActorType.AGENT, Duration.ofMinutes(30));

        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);

        // 第三段必须是 base64url(原始 HMAC 字节)，不是十六进制 ——
        // 这一条曾经写错过（用 hex 与签名段比较），自签自验永远失败。
        assertThat(parts[2]).doesNotContain("=").matches("[A-Za-z0-9_-]+");
        assertThat(parts[2]).isNotEqualTo(hexOfHmac(parts[0] + "." + parts[1]));

        // 用独立实现（手写 base64url + 手写 HMAC 展开式）复算签名并比对，
        // 让「签发端」也接受外部实现的检验，而不是只与自己一致。
        assertThat(parts[2]).isEqualTo(independentSign(parts[0] + "." + parts[1]));

        String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        assertThat(headerJson).isEqualTo("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(payloadJson).contains("\"sub\":\"42\"").contains("\"hdl\":\"bob\"").contains("\"exp\":");
    }

    @Test
    @DisplayName("独立实现签发的 token 也能被接受（避免只在「自己造、自己验」里自洽）")
    void acceptsIndependentlyIssuedToken() {
        String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        long exp = T0.plusSeconds(600).getEpochSecond();
        String payload = b64("{\"sub\":\"7\",\"hdl\":\"carol\",\"atp\":2,\"iat\":"
                + T0.getEpochSecond() + ",\"exp\":" + exp + "}");
        String token = header + "." + payload + "." + independentSign(header + "." + payload);

        TokenClaims claims = at(T0).verify(token);
        assertThat(claims.actorId()).isEqualTo(7L);
        assertThat(claims.actorType()).isEqualTo(ActorType.AGENT);
    }

    /** 断言抛出 TmException 并取出它，便于进一步检查错误码与 detail。 */
    private static TmException failure(Runnable body) {
        return catchThrowableOfType(body::run, TmException.class);
    }

    @Test
    @DisplayName("过期：exp 当秒即失效（now >= exp），错误码 40103 且可重试")
    void expiredIsRejected() {
        JwtTokenService svc = at(T0);
        String token = svc.issue(1L, "a", ActorType.HUMAN, Duration.ofHours(2));

        // 边界：exp 那一秒就算过期，不能出现「过期后仍可用」的窗口
        TmException e = failure(() -> at(T0.plusSeconds(7200)).verify(token));
        assertThat(e.errorCode()).isEqualTo(ErrorCode.TOKEN_EXPIRED);
        assertThat(e.retryable()).isTrue();

        assertThat(failure(() -> at(T0.plusSeconds(7201)).verify(token)).errorCode())
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);
        // 未过期则正常
        assertThat(at(T0.plusSeconds(7199)).verify(token).actorId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("篡改 payload（改 sub）导致验签失败：40102")
    void tamperedPayloadIsRejected() {
        JwtTokenService svc = at(T0);
        String token = svc.issue(1L, "a", ActorType.HUMAN, Duration.ofHours(2));
        String[] p = token.split("\\.");
        String forged = b64("{\"sub\":\"999\",\"iat\":" + T0.getEpochSecond()
                + ",\"exp\":" + T0.plusSeconds(3600).getEpochSecond() + "}");
        String tampered = p[0] + "." + forged + "." + p[2];

        assertThatThrownBy(() -> at(T0).verify(tampered))
                .isInstanceOf(TmException.class)
                .satisfies(e -> assertThat(((TmException) e).errorCode())
                        .isEqualTo(ErrorCode.INVALID_TOKEN_FORMAT));
    }

    @Test
    @DisplayName("alg 混淆攻击被拒：alg=none 与 alg=RS256 都不接受")
    void algorithmConfusionIsRejected() {
        // 经典攻击：把 header 改成 {"alg":"none"}，签名段留空，指望验签方跳过校验。
        String header = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = b64("{\"sub\":\"1\",\"iat\":" + T0.getEpochSecond()
                + ",\"exp\":" + T0.plusSeconds(600).getEpochSecond() + "}");
        assertThatThrownBy(() -> at(T0).verify(header + "." + payload + "."))
                .isInstanceOf(TmException.class);

        // 带一个「合法但算法不对」的签名段，同样必须拒绝
        String bogus = b64("not-a-real-signature");
        assertThatThrownBy(() -> at(T0).verify(header + "." + payload + "." + bogus))
                .isInstanceOf(TmException.class)
                .hasMessageContaining("40102");

        String rs = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        TmException e = failure(() -> at(T0).verify(rs + "." + payload + "." + independentSign(rs + "." + payload)));
        assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_TOKEN_FORMAT);
        assertThat(e.detail()).contains("RS256");
    }

    @Test
    @DisplayName("缺少 exp 的 token 非法，不能被当成「永不过期」")
    void missingExpIsRejected() {
        String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = b64("{\"sub\":\"1\",\"iat\":" + T0.getEpochSecond() + "}");
        String token = header + "." + payload + "." + independentSign(header + "." + payload);

        TmException e = failure(() -> at(T0).verify(token));
        assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_TOKEN_FORMAT);
        assertThat(e.detail()).contains("exp");
    }

    @Test
    @DisplayName("换密钥即失效（不同实例共用错误的 secret 会被立刻发现）")
    void otherSecretIsRejected() {
        String token = new JwtTokenService(SECRET).issue(1L, "a", ActorType.HUMAN, Duration.ofHours(1));
        JwtTokenService other = new JwtTokenService(
                "another-secret-0123456789abcdef01234567", Clock.fixed(T0, ZoneOffset.UTC));
        assertThatThrownBy(() -> other.verify(token))
                .isInstanceOf(TmException.class)
                .hasMessageContaining("签名不匹配");
    }

    @Test
    @DisplayName("畸形输入被拒：空、段数不对、超长、非 base64url")
    void malformedInputsAreRejected() {
        JwtTokenService svc = at(T0);
        for (String bad : new String[]{null, "", "   ", "abc", "a.b", "a.b.c.d", "..", "a..c",
                "!!!.???.###"}) {
            assertThatThrownBy(() -> svc.verify(bad))
                    .as("input=%s", bad)
                    .isInstanceOf(TmException.class);
        }
        // 超长 token 在验签之前就被挡住，避免「先付出解析代价再判断」
        String huge = "a".repeat(JwtTokenService.MAX_TOKEN_CHARS + 1) + ".b.c";
        assertThatThrownBy(() -> svc.verify(huge))
                .isInstanceOf(TmException.class)
                .hasMessageContaining("超长");
    }

    @Test
    @DisplayName("密钥本身的安全下限：空/短密钥直接拒绝构造")
    void secretMustBeStrong() {
        assertThatThrownBy(() -> new JwtTokenService(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TM_JWT_SECRET");
        assertThatThrownBy(() -> new JwtTokenService(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JwtTokenService("short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("过短");
        // 31 字节仍不足，32 字节通过（边界值，避免把 >= 写成 >）
        assertThatThrownBy(() -> new JwtTokenService("x".repeat(31)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new JwtTokenService("x".repeat(32))).isNotNull();
    }

    @Test
    @DisplayName("ttl 必须为正：零或负有效期会让 token 一签发就过期")
    void ttlMustBePositive() {
        JwtTokenService svc = at(T0);
        assertThatThrownBy(() -> svc.issue(1L, "a", ActorType.HUMAN, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.issue(1L, "a", ActorType.HUMAN, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("handle 中的引号不会破坏 payload（走 Jackson 转义）")
    void handleIsJsonEscaped() {
        JwtTokenService svc = at(T0);
        String token = svc.issue(1L, "e\"vil", ActorType.HUMAN, Duration.ofHours(1));
        assertThat(at(T0).verify(token).handle()).isEqualTo("e\"vil");
    }

    // ==================== 独立参照实现（不用本项目的 HmacSha256） ====================

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String independentSign(String signingInput) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                rfc2104(SECRET.getBytes(StandardCharsets.UTF_8),
                        signingInput.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String hexOfHmac(String signingInput) {
        byte[] raw = rfc2104(SECRET.getBytes(StandardCharsets.UTF_8),
                signingInput.getBytes(StandardCharsets.US_ASCII));
        StringBuilder sb = new StringBuilder();
        for (byte b : raw) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** RFC 2104 展开式，不调用 Mac，作为独立参照。 */
    private static byte[] rfc2104(byte[] key, byte[] message) {
        try {
            byte[] k = key.length > 64 ? sha256(key) : key;
            byte[] padded = new byte[64];
            System.arraycopy(k, 0, padded, 0, k.length);
            byte[] ipad = new byte[64];
            byte[] opad = new byte[64];
            for (int i = 0; i < 64; i++) {
                ipad[i] = (byte) (padded[i] ^ 0x36);
                opad[i] = (byte) (padded[i] ^ 0x5c);
            }
            return sha256(concat(opad, sha256(concat(ipad, message))));
        } catch (Exception e) {
            // 仅用于测试的参照实现：MSVC 不涉及，直接抛出即可
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
