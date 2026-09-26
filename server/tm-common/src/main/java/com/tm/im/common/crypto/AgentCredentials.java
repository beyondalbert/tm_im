package com.tm.im.common.crypto;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 服务端生成的两类长期凭证：Agent 的 {@code api_key} 与 Webhook 的 {@code webhook_secret}。
 *
 * <p><b>格式是契约的一部分</b>（02-auth.md §3.1）：{@code sk_live_<随机串>} 与
 * {@code whsec_<随机串>}。前缀不是装饰：
 * <ul>
 *   <li>{@code sk_} 让服务端能把「人类 JWT」与「Agent api_key」分开
 *       （{@link com.tm.im.common.error.ErrorCode} 之外的唯一判据见
 *       {@code CredentialKind.of}），而两者在 HTTP 头里长得完全一样；</li>
 *   <li>{@code whsec_} 让「把两种密钥填反了」这件事在第一次调用就显形 ——
 *       填反的话签名永远不会对，而错误信息只会说「验签失败」。</li>
 * </ul>
 *
 * <p><b>为什么是 128 位随机</b>：这两把凭据都是长期有效的（api_key 直到被轮换、
 * webhook_secret 直到被重签），没有口令那样的「用户可记忆」约束，所以取满
 * 128 位随机（32 个十六进制字符）。文档示例里的 16 个 hex 字符（64 位）只是
 * 排版示例——64 位在如今的算力下不足以抵抗在线爆破，而把长度加倍的代价是零。
 *
 * <p>用 {@link SecureRandom} 而不是 {@code Random}/{@code ThreadLocalRandom}：
 * 后者是可预测的，而「可预测的长期密钥」等于没有密钥（同一进程启动后一段时间内的
 * 输出可以被反推）。
 */
public final class AgentCredentials {

    /** Agent 凭证前缀（与 {@code CredentialKind.API_KEY_PREFIX} 一致，改动即改对外协议）。 */
    public static final String API_KEY_PREFIX = "sk_live_";

    /** Webhook 密钥前缀。 */
    public static final String WEBHOOK_SECRET_PREFIX = "whsec_";

    /** 随机部分的字节数：16 字节 = 128 位 → 32 个十六进制字符。 */
    private static final int RANDOM_BYTES = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private AgentCredentials() {
    }

    /** 新的 api_key：{@code sk_live_} + 32 位十六进制。 */
    public static String newApiKey() {
        return API_KEY_PREFIX + randomHex();
    }

    /** 新的 webhook_secret：{@code whsec_} + 32 位十六进制。 */
    public static String newWebhookSecret() {
        return WEBHOOK_SECRET_PREFIX + randomHex();
    }

    /**
     * 判断一个凭证是否是 Agent api_key 的形状。
     *
     * <p>只用于「这像不像 api_key」的前置判断，<b>不用于鉴权</b>：
     * 真正的鉴权是「拿哈希去库里查」（见 {@code IdentityService.resolveByApiKey}）。
     */
    public static boolean looksLikeApiKey(String credential) {
        return credential != null && credential.startsWith(API_KEY_PREFIX);
    }

    private static String randomHex() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        // HexFormat 而不是 ByteBuffer + 手写循环：这里没有「byte 有符号」的坑
        // （那个坑在 Digests 里被显式处理过），因为 HexFormat 按无符号补零。
        return HexFormat.of().formatHex(bytes);
    }
}
