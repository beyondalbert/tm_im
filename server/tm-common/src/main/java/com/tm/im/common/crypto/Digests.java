package com.tm.im.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 摘要工具：把凭证单向化后落库。
 *
 * <p><b>适用范围有严格边界，越界即漏洞：</b>
 * <ul>
 *   <li>✅ <b>API Key</b>（本类现在的唯一用途）。api_key 是服务端生成的
 *       高熵随机串（{@code sk_live_} + 128 位随机），攻击者没有可猜测的空间，
 *       因此不需要「慢哈希」——SHA-256 足够，且单次鉴权成本可忽略。</li>
 *   <li>❌ <b>用户密码</b>。密码熵低（人选的），快速哈希意味着离线爆破可以
 *       每秒试上亿次。密码必须用 bcrypt / argon2 这类<b>刻意慢</b>的算法，
 *       见 {@code SecretType.PASSWORD_HASH}。做密码登录（M3）时不要图省事
 *       复用这个方法，那会是一个只在被拖库后才暴露的设计缺陷。</li>
 * </ul>
 *
 * <p>注意 {@code actor_secret.secret_hash} 列同时存放两类哈希，两者不会有
 * 碰撞风险：本类输出定长 64 位十六进制，bcrypt 输出 {@code $2a$...} 前缀串，
 * 取值空间不重叠。
 */
public final class Digests {

    /** {@link #sha256Hex} 的输出长度（64 个 hex 字符）。调用方可据此校验列宽。 */
    public static final int SHA256_HEX_LENGTH = 64;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Digests() {
    }

    public static String sha256Hex(String plain) {
        if (plain == null) {
            throw new IllegalArgumentException("plain 不能为 null");
        }
        return sha256Hex(plain.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * SHA-256 摘要，小写十六进制。
     *
     * <p>刻意手写十六进制转换而不是用 {@link HexFormat#of()}：{@code digest()}
     * 返回的是 {@code byte[]}，而 {@code byte} 在 Java 里<b>有符号</b>，
     * 用 {@code String.format("%02x", b)} 处理负字节会得到 {@code ffffff80}
     * 这样的 8 字符输出，哈希长度随机变化。这类错误平时不报错，
     * 只在某些密钥上表现为「认证偶发失败」。
     */
    public static String sha256Hex(byte[] data) {
        MessageDigest md = newDigest();
        byte[] digest = md.digest(data);
        char[] out = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int v = digest[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须实现的标准算法（JLS/JCA 规范要求），
            // 走到这里说明运行环境被裁剪过，属于不可恢复的环境问题。
            throw new IllegalStateException("运行环境缺少 SHA-256 实现", e);
        }
    }
}
