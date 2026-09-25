package com.tm.im.common.crypto;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * 密码哈希（PBKDF2-HMAC-SHA256）。
 *
 * <p><b>为什么不是「sha256(密码 + salt)」</b>：那类构造单次计算只要微秒级，
 * 拿到库之后用一块显卡每秒能试上亿个候选口令；而本系统的口令最短 8 位
 * （02-auth.md §2.1），字典 + 规则组合的空间在那种速度下是可枚举的。
 * PBKDF2 把单次校验的成本抬到毫秒级并且<b>可调</b>，这类攻击的代价才变得不可接受。
 *
 * <p><b>为什么不引 spring-security-crypto / bcrypt 库</b>：本仓库已经在
 * {@link HmacSha256} 与 JWT 上做过同一个取舍（见 {@code JwtTokenService} 的类注释）。
 * 这里再多一条具体理由：一旦引入 spring-security-crypto，同一个 classpath 上会同时存在
 * 「只因为要一个 hash 函数」而拉进来的安全库和自研的鉴权逻辑，两套安全假设并存，
 * 后来者无从判断哪套在生效。PBKDF2 是 JDK 自带的算法，够用且没有这层歧义。
 *
 * <p><b>编码格式自带参数</b>：
 * <pre>
 *   pbkdf2-sha256$&lt;iterations&gt;$&lt;salt-base64url&gt;$&lt;hash-base64url&gt;
 * </pre>
 * 迭代次数写在字符串里（而不是「读当前配置」），因为它是<b>历史事实</b>：
 * 以后把默认迭代数从 21 万提到 60 万时，老用户的哈希必须仍然能用老参数验证通过，
 * 否则一次参数调整就等于所有人都被登出。{@link #verify} 用存下来的参数校验，
 * {@link #needsRehash} 让调用方在登录成功那一刻顺手升级。
 *
 * <p><b>返回值语义（重要）</b>：
 * <ul>
 *   <li>{@link #verify} 对「口令不对」返回 {@code false}，对「存下来的哈希格式坏了」
 *       抛异常。两者不能合并——合并之后，一行被截断的哈希会把用户永久锁在
 *       门外，而日志里只有一串「密码错误」。</li>
 *   <li>比较用 {@link MessageDigest#isEqual}（恒定时间）。用 {@code equals} 比较
 *       hex 字符串会因提前返回而泄漏「前多少字节是对的」。</li>
 * </ul>
 *
 * <p><b>本类不做口令强度校验</b>：长度上限之类属于「注册流程」的策略（错误码 40002），
 * 在校验层实现；哈希函数只管「给定口令与盐，算出结果」，这样它才是纯函数、可穷举测试。
 */
public final class PasswordHashes {

    /** 格式前缀。改动它等于让所有存量哈希失效，因此它是稳定契约。 */
    public static final String ALGORITHM = "pbkdf2-sha256";

    /**
     * 默认迭代次数（OWASP 2023 对 PBKDF2-HMAC-SHA256 的建议下限是 600k；
     * 这里取 21 万是「单次校验约 50ms 以内、能扛住 30 核机器的并发登录」的折中）。
     *
     * <p>注意这个值<b>只影响新哈希</b>：验证时用的是字符串里记录的迭代数。
     */
    public static final int DEFAULT_ITERATIONS = 210_000;

    /** 盐长度。低于 8 字节会让「同一口令在不同账号上哈希相同」的概率变得可观测。 */
    public static final int SALT_BYTES = 16;

    /** 派生密钥长度。256 位＝与 HMAC-SHA256 的输出宽度一致，再长没有额外收益。 */
    static final int KEY_BITS = 256;

    private static final String SEPARATOR = "$";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private PasswordHashes() {
    }

    /** 用默认迭代数哈希。返回的字符串可直接落库（{@code actor_secret.secret_hash}）。 */
    public static String hash(String password) {
        return hash(password, DEFAULT_ITERATIONS);
    }

    /**
     * @param iterations 必须为正。显式传入是为了测试能跑得快，以及让「参数升级」
     *                   有一条不依赖读配置的路径。
     */
    public static String hash(String password, int iterations) {
        requirePassword(password);
        if (iterations <= 0) {
            throw new IllegalArgumentException("iterations 必须为正: " + iterations);
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = derive(password, salt, iterations);
        return ALGORITHM + SEPARATOR + iterations + SEPARATOR
                + ENCODER.encodeToString(salt) + SEPARATOR + ENCODER.encodeToString(derived);
    }

    /**
     * @return 口令是否匹配。口令不对返回 {@code false}；
     *         {@code stored} 结构非法则抛 {@link IllegalArgumentException}。
     * @throws IllegalArgumentException stored 为空、算法前缀不认识、迭代数非数字或
     *                                  非正、base64 解不开。这些都是「库里的数据坏了」
     *                                  或「有人手改了表」，属于服务端问题，不该伪装成
     *                                  「口令错误」。
     */
    public static boolean verify(String password, String stored) {
        if (password == null) {
            // 口令为 null 是调用方的 bug（Spring 的 JSON 绑定可能给出 null），
            // 判成「不匹配」会让这个 bug 表现为「用户总是登录失败」。
            throw new IllegalArgumentException("password 为 null");
        }
        Parsed parsed = parse(stored);
        byte[] actual = derive(password, parsed.salt(), parsed.iterations());
        return MessageDigest.isEqual(parsed.hash(), actual);
    }

    /**
     * 该哈希是否用当前默认参数生成的。登录成功后可据此静默升级
     * （升级需要明文口令，而那只在校验的那一瞬间存在）。
     */
    public static boolean needsRehash(String stored) {
        return parse(stored).iterations() < DEFAULT_ITERATIONS;
    }

    /** 迭代数（供测试与运维排查用；不参与任何判断逻辑）。 */
    public static int iterationsOf(String stored) {
        return parse(stored).iterations();
    }

    private record Parsed(int iterations, byte[] salt, byte[] hash) {
    }

    private static Parsed parse(String stored) {
        if (stored == null || stored.isBlank()) {
            throw new IllegalArgumentException("存下来的密码哈希为空");
        }
        String[] parts = stored.split("\\" + SEPARATOR, -1);
        // 用 -1 保留末尾空串：'a$b$c$' 这种被截断的值要落到长度检查上，
        // 而不是被 split 悄悄吃掉最后一段后变成一个「看起来合法」的三段式。
        if (parts.length != 4) {
            throw new IllegalArgumentException(
                    "密码哈希不是 4 段（算法$迭代数$盐$摘要），实际 " + parts.length + " 段");
        }
        if (!ALGORITHM.equals(parts[0])) {
            throw new IllegalArgumentException("不认识的密码哈希算法: " + parts[0]);
        }
        int iterations;
        try {
            iterations = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("迭代数不是整数: " + parts[1]);
        }
        if (iterations <= 0) {
            throw new IllegalArgumentException("迭代数非正: " + iterations);
        }
        byte[] salt = decode(parts[2], "盐");
        byte[] hash = decode(parts[3], "摘要");
        if (salt.length == 0 || hash.length == 0) {
            throw new IllegalArgumentException("盐或摘要为空");
        }
        return new Parsed(iterations, salt, hash);
    }

    private static byte[] decode(String part, String what) {
        try {
            return DECODER.decode(part);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(what + "不是合法 base64url");
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } catch (NoSuchAlgorithmException e) {
            // PBKDF2WithHmacSHA256 是 JDK 8+ 的必备算法，走到这里说明运行环境被人动过。
            throw new IllegalStateException("运行环境缺少 PBKDF2WithHmacSHA256", e);
        } catch (InvalidKeySpecException e) {
            throw new IllegalArgumentException("口令不符合 PBKDF2 的输入要求", e);
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * 口令本身不设格式限制（只要求非 null、非空）：长度与字符集策略在注册校验里，
     * 这里拦一道是为了让「hash(null)」不会变成一个能落库的哈希——
     * 那会让某个账号的密码变成「任意口令都能登录」。
     *
     * <p>口令经 {@code char[]} 交给 PBEKeySpec（PBKDF2 规范里口令是字节串，
     * JCE 实现按 UTF-8 编码这些字符），所以中文/emoji 口令在任何平台上的结果一致。
     * 这一点由测试钉住：跨平台不一致的密码哈希，表现是「换个 JDK 就登录不上」。
     */
    private static void requirePassword(String password) {
        if (password == null) {
            throw new IllegalArgumentException("password 为 null");
        }
        if (password.isEmpty()) {
            throw new IllegalArgumentException("password 为空串");
        }
    }
}
