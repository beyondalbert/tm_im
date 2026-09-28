package com.tm.im.common.crypto;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 不透明凭证的生成与哈希 —— {@code rt_}（refresh_token）与 {@code adm_}（后台会话）
 * 共用这一份实现。
 *
 * <p><b>为什么把这两者抽出来</b>：它们的形状、熵、存储方式（只存哈希）与校验方式
 * （拿哈希查表）完全一致，唯一的差别是<b>前缀</b>——而前缀恰恰是最不该有两份实现的部分：
 * 两份实现里的随机数长度迟早会漂移（一个 32 字节、一个 16 字节），
 * 而那种漂移在功能上完全正常，只有熵静默变低。
 *
 * <p><b>为什么不引 JWT 或其他库</b>：见 {@link RefreshTokens} 的类注释——
 * 这里的凭证是「256 位随机数 + 一次 O(1) 查表」，不需要签名，也不该有算法协商。
 *
 * <p><b>前缀是协议的一部分</b>：它让「这是哪一种凭证」在日志、抓包与工单里一眼可辨，
 * 也让「把 refresh_token 贴到后台接口上」这种串用在第一层就被拒。
 * 改动任何前缀等于让存量凭证全部失效。
 */
public final class OpaqueToken {

    /** 随机部分字节数。256 位，暴力枚举不可行。 */
    static final int RANDOM_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private OpaqueToken() {
    }

    /**
     * 生成一个新凭证（明文）。<b>这是唯一一次能拿到明文的机会</b>——
     * 调用方必须在这一次把它交给用户，之后库里只剩哈希。
     *
     * @param prefix 形如 {@code "rt_"}。校验它而不是「随便传」：
     *               空前缀会让凭证与随机串无从区分，也让它失去上面说的那层保护。
     */
    public static String generate(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            throw new IllegalArgumentException("凭证前缀不能为空");
        }
        byte[] raw = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(raw);
        return prefix + ENCODER.encodeToString(raw);
    }

    /**
     * 明文 → 存储用的哈希（小写 hex SHA-256）。
     *
     * <p>用不加盐的 SHA-256 而不是 {@link PasswordHashes}：凭证是 256 位随机数，
     * 不存在字典攻击，需要的是「鉴权时一次 O(1) 查找」；换成慢哈希会把每次请求
     * 拖成几十毫秒，而防的东西一模一样。
     *
     * <p>对空白/非法格式<b>不做校验、直接哈希</b>：查不到就是查不到，
     * 而「格式错」与「不存在」对外必须是同一个错误码——
     * 在这里区分只会让攻击者能用响应差异探测凭证格式。
     */
    public static String hash(String plain) {
        return Digests.sha256Hex(plain);
    }

    /** 这个字符串是不是该前缀的凭证（只做形状判断，<b>不用于鉴权</b>）。 */
    public static boolean hasPrefix(String prefix, String token) {
        return prefix != null && token != null && token.startsWith(prefix);
    }

    /**
     * 日志里可以安全打印的形式：只留前缀与长度。
     *
     * <p>它的存在意义是「让『把凭证打进日志』这件事有一个正确的写法」——
     * 否则代码里出现的就是 {@code log.info("token={}", token)}。
     */
    public static String fingerprint(String prefix, String token) {
        if (token == null) {
            return "<null>";
        }
        if (!hasPrefix(prefix, token)) {
            // 前缀都不对时连长度都不该给：那已经是一个「不像凭证」的输入。
            return "<not-" + (prefix == null ? "?" : prefix) + ">";
        }
        return prefix + "…(" + (token.length() - prefix.length()) + " 字符)";
    }
}
