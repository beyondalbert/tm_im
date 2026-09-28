package com.tm.im.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OpaqueToken} 是 refresh_token 与后台会话共用的那一份实现，
 * 所以这里的断言是两者的共同契约（{@link RefreshTokensTest} 断言的是 rt_ 那一侧）。
 */
class OpaqueTokenTest {

    private static final String PREFIX = "adm_";

    @Test
    @DisplayName("前缀 + 32 字节 base64url —— 熵与长度都不随前缀变化")
    void formatIsPrefixPlus256Bits() {
        String token = OpaqueToken.generate(PREFIX);

        assertThat(token).startsWith(PREFIX);
        // 32 字节 → 43 个 base64url 字符（无填充）。长度是契约的一部分。
        assertThat(token).hasSize(PREFIX.length() + 43);
        assertThat(token.substring(PREFIX.length())).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    @DisplayName("每次生成都不同（SecureRandom，而不是 UUID 的 122 位）")
    void tokensAreUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            seen.add(OpaqueToken.generate(PREFIX));
        }
        assertThat(seen).hasSize(500);
    }

    @Test
    @DisplayName("换前缀不换熵：不同前缀的凭证长度一致")
    void entropyDoesNotDependOnPrefix() {
        // 这条断言的由来：两个前缀曾经各有一份生成实现，
        // 其中一份把字节数写成了 16 —— 功能全对，只是熵静默减半。
        String rt = OpaqueToken.generate("rt_");
        String adm = OpaqueToken.generate("adm_");
        assertThat(rt.substring(3)).hasSize(adm.substring(4).length());
    }

    @Test
    @DisplayName("空前缀直接拒绝：它会让凭证与随机串无从区分")
    void emptyPrefixIsRejected() {
        assertThatThrownBy(() -> OpaqueToken.generate(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpaqueToken.generate(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("哈希是 64 位小写 hex 的 SHA-256，同值同哈希")
    void hashIsSha256Hex() {
        String token = OpaqueToken.generate(PREFIX);
        String hash = OpaqueToken.hash(token);

        assertThat(hash).hasSize(Digests.SHA256_HEX_LENGTH).matches("[0-9a-f]{64}");
        assertThat(OpaqueToken.hash(token)).isEqualTo(hash);
        assertThat(hash).isEqualTo(Digests.sha256Hex(token));
    }

    @Test
    @DisplayName("对空白输入也照常哈希：格式错与不存在对外必须是同一个错误码")
    void blankInputIsHashedNotRejected() {
        assertThat(OpaqueToken.hash("")).hasSize(Digests.SHA256_HEX_LENGTH);
        assertThat(OpaqueToken.hash("  ")).hasSize(Digests.SHA256_HEX_LENGTH);
    }

    @Test
    @DisplayName("hasPrefix 是形状判断（不用于鉴权）；fingerprint 不泄漏凭证本身")
    void prefixAndFingerprint() {
        String token = OpaqueToken.generate(PREFIX);
        assertThat(OpaqueToken.hasPrefix(PREFIX, token)).isTrue();
        assertThat(OpaqueToken.hasPrefix("rt_", token)).isFalse();
        assertThat(OpaqueToken.hasPrefix(PREFIX, null)).isFalse();

        String shown = OpaqueToken.fingerprint(PREFIX, token);
        assertThat(shown).isEqualTo(PREFIX + "…(43 字符)");
        // 只有前缀是对的字符串才配得到「前缀 + 长度」这种描述
        assertThat(OpaqueToken.fingerprint(PREFIX, "rt_abcdef")).isEqualTo("<not-adm_>");
        assertThat(OpaqueToken.fingerprint(PREFIX, null)).isEqualTo("<null>");
    }
}
