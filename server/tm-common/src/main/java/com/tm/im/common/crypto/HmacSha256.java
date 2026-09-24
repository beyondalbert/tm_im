package com.tm.im.common.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * HMAC-SHA256：本系统所有「共享密钥 + 签名」场景的唯一实现。
 *
 * <p>使用方：JWT 的 HS256 签名校验（长连接鉴权）与 Webhook 回调验签
 * （{@code docs/integration/02-auth.md} §6 —— 那里的自测向量被本类的测试
 * 直接引用，见 {@code HmacSha256Test}）。
 *
 * <p><b>为什么不引第三方 JWT / HMAC 库</b>：HS256 的验签是「一把钥匙 + 一个
 * 标准算法」，JDK 自带 {@code Mac}。引入 jjwt 之类会连带 jackson-databind、
 * 版本须与 Spring Boot BOM 对齐（本仓库已被 BOM 向下覆盖坑过一次，
 * 见 DESIGN §3.3 陷阱 4）。代价是必须自己把 JWT 的几处协议级陷阱处理干净，
 * 它们集中在 {@code JwtTokenService}，并各有针对性测试。
 *
 * <p><b>比较必须用 {@link #constantTimeEquals}</b>：直接 {@code a.equals(b)}
 * 会在第一个不同字节处返回，攻击者可据此逐字节试探出正确签名（时序攻击）。
 * 本类不提供任何「非恒定时间」的比较方法，从源头堵住误用。
 */
public final class HmacSha256 {

    private static final String ALGORITHM = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private HmacSha256() {
    }

    public static byte[] mac(byte[] key, byte[] message) {
        // 空密钥自行先挡：SecretKeySpec 会抛 IllegalArgumentException("Empty key")，
        // 消息里既没有上下文也不指向任何配置项。而这种输入在实际系统里
        // 几乎必然是「配置没读到」（TM_JWT_SECRET 未注入），不是有意为之，
        // 所以在这里换成一句能直接指向排查方向的话。
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException(
                    "HMAC 密钥为空——通常是密钥配置未注入（如 TM_JWT_SECRET），而不是有意为之");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(message);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 HmacSHA256 实现", e);
        } catch (InvalidKeyException e) {
            // 走到这里说明密钥被 JCE provider 判为非法（长度/类型）。
            // 注意 SunJCE 对 HmacSHA256 不设最小长度，因此这条分支在生产上
            // 基本只会在换了 provider 或用了特殊密钥类型时出现。
            throw new IllegalArgumentException("HMAC 密钥被 JCE 判为非法", e);
        }
    }

    public static byte[] mac(String key, String message) {
        return mac(bytes(key), bytes(message));
    }

    /** 小写十六进制签名值。 */
    public static String hex(byte[] key, byte[] message) {
        return toHex(mac(key, message));
    }

    public static String hex(String key, String message) {
        return toHex(mac(key, message));
    }

    public static String toHex(byte[] raw) {
        char[] out = new char[raw.length * 2];
        for (int i = 0; i < raw.length; i++) {
            int v = raw[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    /**
     * 恒定时间比较。
     *
     * <p>长度不同时 {@link MessageDigest#isEqual} 会立即返回 {@code false}——
     * 这一步会泄漏「长度」信息。对本系统的用法这是可接受的：签名长度恒为
     * 64（十六进制），token 长度本身不是秘密。真正必须守住的是<b>内容</b>
     * 逐字节比较不提前退出。
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        return MessageDigest.isEqual(bytes(a), bytes(b));
    }

    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null) {
            return a == b;
        }
        return MessageDigest.isEqual(a, b);
    }

    private static byte[] bytes(String s) {
        if (s == null) {
            throw new IllegalArgumentException("参数不能为 null");
        }
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
