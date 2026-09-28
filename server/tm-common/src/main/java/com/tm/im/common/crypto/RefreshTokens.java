package com.tm.im.common.crypto;

import java.util.Base64;
/**
 * refresh_token 的生成与规范化（02-auth.md §2.3）。
 *
 * <p><b>格式</b>：{@code rt_<43 个 base64url 字符>}，即 32 字节的密码学随机数。
 * 前缀不是装饰：它让「这是哪一种凭证」在日志、抓包、工单里一眼可辨，
 * 与 api_key 的 {@code sk_} 前缀、JWT 的三段式并列为同一套约定。
 *
 * <p><b>32 字节、不用 UUID</b>：{@code UUID.randomUUID()} 只有 122 位随机性，
 * 而且版本位与变体位是固定值——对「不可预测」这个唯一要求来说，
 * 它是在用更长的字符串换更少的熵。这里用 {@link SecureRandom} 直接取 32 字节。
 *
 * <p><b>落库/落 Redis 的只有哈希</b>：与 api_key 一致（{@code actor_secret} 里存
 * SHA-256）。理由同样是「读库的人不该拿到能直接用的凭证」。这里用不加盐的 SHA-256
 * 而不是 {@link PasswordHashes} —— 两者防的东西不同：
 * 口令是人选的、空间小，所以要慢哈希；refresh_token 是 256 位随机数，
 * 不存在字典攻击，需要的是「鉴权时一次 O(1) 查找」，用慢哈希反而会把每个
 * 刷新请求拖成几十毫秒。
 */
public final class RefreshTokens {

    /** 前缀。改动它等于让所有存量 refresh_token 失效（那会让全体用户被登出）。 */
    public static final String PREFIX = "rt_";

    /** 随机部分字节数。256 位，暴力枚举不可行。 */
    static final int RANDOM_BYTES = OpaqueToken.RANDOM_BYTES;

    private RefreshTokens() {
    }

    /**
     * 生成一个新凭证（明文）。这是唯一一次能拿到明文的机会。
     *
     * <p>实现委托给 {@link OpaqueToken}（与后台会话 {@code adm_} 同一份代码）：
     * 两者的形状、熵与「只存哈希」完全一样，差别只有前缀——
     * 两份实现里随机长度迟早会漂移，而那种漂移不会报错，只会静默降低熵。
     */
    public static String generate() {
        return OpaqueToken.generate(PREFIX);
    }

    /**
     * 明文 → 存储用的哈希（小写 hex SHA-256）。
     *
     * <p>对空白/非法格式<b>不做校验、直接哈希</b>：查不到就是查不到，
     * 而「格式错」与「不存在」对外必须是同一个 40104（02-auth.md §4）。
     * 在这里区分只会让攻击者能用响应差异探测凭证格式。
     */
    public static String hash(String plain) {
        return OpaqueToken.hash(plain);
    }
}
