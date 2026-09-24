package com.tm.im.core.identity;

/**
 * 客户端出示的凭证种类。
 *
 * <p>判定依据是<b>凭证本身的前缀</b>，不是账号的类型：
 * {@code sk_} 开头为 api_key，其余按 JWT 处理（见 docs/integration/02-auth.md §1）。
 *
 * <p>刻意<b>不</b>做「api_key 只能属于 Agent」的校验。理由是「对等」红线：
 * 凭证种类属于「怎么证明你是谁」，账号种类属于「你是谁」，把两者绑死之后，
 * 「给一个人类账号发长期密钥」（CI、个人自动化脚本的常见需求）
 * 就需要改表、改鉴权、改文档三处。绑定带来的安全性提升是零——
 * 能签发 actor_secret 行的只有平台自己。
 */
public enum CredentialKind {

    /** 人类：HS256 JWT，短期有效，靠 refresh_token 续。 */
    JWT,

    /** Agent：长期有效的 api_key，落库只存哈希。 */
    API_KEY;

    /** 凭证前缀，服务端据此分派。改动它等于改对外协议，需同步 02-auth.md。 */
    public static final String API_KEY_PREFIX = "sk_";

    public static CredentialKind of(String credential) {
        return credential != null && credential.startsWith(API_KEY_PREFIX)
                ? API_KEY : JWT;
    }
}
