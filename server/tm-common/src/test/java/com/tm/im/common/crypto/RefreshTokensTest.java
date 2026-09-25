package com.tm.im.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokensTest {

    @Test
    @DisplayName("前缀 + 32 字节 base64url，正好 46 个字符")
    void formatIsStable() {
        String token = RefreshTokens.generate();

        assertThat(token).startsWith(RefreshTokens.PREFIX);
        // 32 字节 → 43 个 base64 字符（无填充）。这个长度是契约的一部分：
        // 客户端与运维脚本会按它做正则校验，改长度等于改协议。
        assertThat(token).hasSize(RefreshTokens.PREFIX.length() + 43);
        assertThat(token.substring(RefreshTokens.PREFIX.length()))
                .matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    @DisplayName("每次都不同（SecureRandom 32 字节，不是 UUID）")
    void generatesUniqueTokens() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            seen.add(RefreshTokens.generate());
        }
        assertThat(seen).hasSize(500);
    }

    @Test
    @DisplayName("哈希是 64 位小写 hex 的 SHA-256，且同值同哈希")
    void hashIsSha256Hex() {
        String token = RefreshTokens.generate();

        String hash = RefreshTokens.hash(token);
        assertThat(hash).hasSize(Digests.SHA256_HEX_LENGTH).matches("[0-9a-f]{64}");
        assertThat(RefreshTokens.hash(token)).isEqualTo(hash);
        // 与 Digests 的既有实现必须完全一致：键名由它派生，
        // 两条实现若分叉，「同一凭证算出两个 key」会让所有刷新在 Redis 里找不到会话
        assertThat(hash).isEqualTo(Digests.sha256Hex(token));
    }

    @Test
    @DisplayName("对空白输入也照常哈希，不抛异常")
    void blankInputIsHashedNotRejected() {
        // 查不到就是查不到，对外必须是同一个 40104。
        // 在这里按格式拒绝会让攻击者能用响应差异分辨「格式错」与「不存在」。
        assertThat(RefreshTokens.hash("")).hasSize(Digests.SHA256_HEX_LENGTH);
        assertThat(RefreshTokens.hash("  ")).hasSize(Digests.SHA256_HEX_LENGTH);
    }
}
