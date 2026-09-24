package com.tm.im.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HMAC-SHA256 的正确性证据。
 *
 * <p>这条原语一旦写错，症状是「所有 token 都验不过」或更糟的
 * 「不该过的 token 也过了」，而两者都不会指出问题在 HMAC 上。
 * 因此这里用<b>两个互相独立</b>的参照物：
 * <ol>
 *   <li>接入文档里给定的自测向量（由文档作者用 Python {@code hmac} 生成）；</li>
 *   <li>按 RFC 2104 定义<b>手写</b>的 HMAC 展开式（不调用 {@code Mac}），
 *       用于验证我们没有误用 JCE 的 API。</li>
 * </ol>
 */
class HmacSha256Test {

    /** 来源：docs/integration/02-auth.md §6.4「自测向量」。改文档必须同步改这里。 */
    private static final String WEBHOOK_SECRET = "whsec_3a7f9c2e5b8d1046";
    private static final String TIMESTAMP = "1767225600";
    private static final String BODY = """
            {"event":"message.created","event_id":"evt_01HQ2X3Y4Z5A6B7C8D9E0F",\
            "occurred_at":1767225600456,"data":{"message_id":730000000000000002,\
            "conv_id":1001,"seq":8,"sender_id":2002,"msg_type":1,\
            "content":{"text":"收到，今天北京晴"}}}""";
    private static final String EXPECTED_SIGNATURE =
            "eefcc660e1b273f9a9c36eedc162ccda38380f638d69c434fff0ac633b035712";

    @Test
    @DisplayName("命中接入文档 §6.4 的自测向量（签名原文 = timestamp + \".\" + body）")
    void matchesDocumentedVector() {
        // 中文在 UTF-8 下是 3 字节：body 共 235 字节。长度不对说明测试源码
        // 或编译编码出了问题，而不是算法错了 —— 先报长度能把这两类失败分开。
        assertThat(BODY.getBytes(StandardCharsets.UTF_8)).hasSize(235);

        String signingString = TIMESTAMP + "." + BODY;
        assertThat(HmacSha256.hex(WEBHOOK_SECRET, signingString))
                .isEqualTo(EXPECTED_SIGNATURE);
    }

    @Test
    @DisplayName("与 RFC 2104 手写展开式逐字节一致（验证没有误用 JCE API）")
    void agreesWithHandWrittenRfc2104() {
        // HMAC(K, m) = H((K' ⊕ opad) ‖ H((K' ⊕ ipad) ‖ m))，K' = 左补零到 64 字节
        // 注意这里没有「空密钥」用例：它被实现刻意拒绝（见下一个测试），
        // 而 RFC 2104 对空密钥的定义在业务上无意义。
        String[][] cases = {
                {"k", "m"},
                {"short", "a longer message 中文也要覆盖"},
                // 密钥长于分组长度（64 字节）时必须先哈希，这是最容易漏的分支
                {"x".repeat(100), "payload"},
                {"\u0000\u0001\u0002", "binary-ish"},
        };
        for (String[] c : cases) {
            assertThat(HmacSha256.hex(c[0], c[1]))
                    .as("key=%r msg=%r", c[0], c[1])
                    .isEqualTo(rfc2104Reference(c[0].getBytes(StandardCharsets.UTF_8),
                            c[1].getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    @DisplayName("空密钥被拒，且错误信息指向配置项而不是 JCE 的 'Empty key'")
    void rejectsEmptyKeyWithActionableMessage() {
        assertThatThrownBy(() -> HmacSha256.mac("", "m"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TM_JWT_SECRET");
        assertThatThrownBy(() -> HmacSha256.mac((byte[]) null, new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("constantTimeEquals：内容相同为 true，长度或内容不同为 false，null 不炸")
    void constantTimeEqualsBehaves() {
        String a = EXPECTED_SIGNATURE;
        assertThat(HmacSha256.constantTimeEquals(a, EXPECTED_SIGNATURE)).isTrue();
        assertThat(HmacSha256.constantTimeEquals(a, EXPECTED_SIGNATURE.substring(0, 62) + "00")).isFalse();
        assertThat(HmacSha256.constantTimeEquals(a, a.substring(0, 63))).isFalse();
        assertThat(HmacSha256.constantTimeEquals(a, "")).isFalse();
        assertThat(HmacSha256.constantTimeEquals((String) null, (String) null)).isTrue();
        assertThat(HmacSha256.constantTimeEquals(a, (String) null)).isFalse();
    }

    /** 独立参照实现：只用 MessageDigest，不碰 Mac。 */
    private static String rfc2104Reference(byte[] key, byte[] message) {
        final int blockSize = 64;
        byte[] k = key.length > blockSize ? sha256(key) : key;
        byte[] padded = new byte[blockSize];
        System.arraycopy(k, 0, padded, 0, k.length);

        byte[] ipad = new byte[blockSize];
        byte[] opad = new byte[blockSize];
        for (int i = 0; i < blockSize; i++) {
            ipad[i] = (byte) (padded[i] ^ 0x36);
            opad[i] = (byte) (padded[i] ^ 0x5c);
        }
        return HmacSha256.toHex(sha256(concat(opad, sha256(concat(ipad, message)))));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
