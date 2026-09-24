package com.tm.im.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DigestsTest {

    @Test
    @DisplayName("SHA-256 输出定长 64 位小写十六进制（负字节不能被格式化成 ffffffXX）")
    void sha256HexIsFixedWidthLowercase() {
        // 覆盖所有字节取值：若实现里用了 String.format("%02x", byte)，
        // 高位字节 >= 0x80 时会产出 8 个字符，长度就不再恒为 64。
        byte[] allBytes = new byte[256];
        for (int i = 0; i < 256; i++) {
            allBytes[i] = (byte) i;
        }
        String hex = Digests.sha256Hex(allBytes);
        assertThat(hex).hasSize(Digests.SHA256_HEX_LENGTH).isEqualTo(hex.toLowerCase());
        assertThat(hex).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("与 JDK 的十六进制格式化独立比对")
    void agreesWithJdkHexFormat() throws Exception {
        for (String s : new String[]{"", "a", "sk_live_9f2c1d7a4b8e3f60", "中文密钥", "x".repeat(1000)}) {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            assertThat(Digests.sha256Hex(s))
                    .as("input=%s", s)
                    .isEqualTo(HexFormat.of().formatHex(digest));
        }
    }

    @Test
    @DisplayName("同一个 api_key 每次哈希相同（靠它做等值查询），不同 key 不碰撞")
    void stableAndDistinct() {
        String key = "sk_live_9f2c1d7a4b8e3f60";
        assertThat(Digests.sha256Hex(key)).isEqualTo(Digests.sha256Hex(key));
        assertThat(Digests.sha256Hex(key)).isNotEqualTo(Digests.sha256Hex(key + "0"));
        assertThatThrownBy(() -> Digests.sha256Hex((String) null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
