package com.tm.im.core.identity;

import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.common.crypto.HmacSha256;
import com.tm.im.common.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.tm.im.domain.enums.ActorType;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * JWT（HS256）签发与校验。本系统<b>同时</b>是签发方与校验方，这一点决定了几处设计。
 *
 * <p><b>为什么自己实现</b>：HS256 验签 = base64url + HMAC-SHA256 + 几个字段检查，
 * JDK 齐备。引 jjwt 会连带 jackson-databind 的版本对齐问题，而本仓库已经被
 * 「Boot BOM 向下覆盖」坑过一次（DESIGN §3.3 陷阱 4）。
 * 代价是必须自己处理干净下面这些协议级陷阱，它们每一条都有对应的测试：
 *
 * <ol>
 *   <li><b>算法混淆</b>：绝不「读 header 里的 alg 再据此选择算法」。那样攻击者
 *       把 alg 改成 {@code none} 或 {@code HS256→RS256} 就可能绕过验签。
 *       本实现的算法是编译期常量，header 里的 alg 只用于<b>比对</b>，
 *       不等于 {@code HS256} 即拒绝。</li>
 *   <li><b>签名必须覆盖 header.payload 原文</b>（拼接后的 ASCII 字节），
 *       而不是解码后的 JSON —— 否则空格、键序的差异会造成「同样内容、不同签名」。</li>
 *   <li><b>比较用恒定时间</b>：直接 equals 会泄漏前缀匹配长度。</li>
 *   <li><b>先验签、后解析业务字段</b>：顺序反了就等于拿未经验证的数据做决策。</li>
 *   <li><b>过期时间必须校验</b>，且 {@code exp} 缺失要当成非法而不是「永不过期」。</li>
 * </ol>
 *
 * <p><b>关于时钟容差</b>：{@code exp} 判定不设宽限（leeway=0）。签发方与校验方
 * 是本系统自己，不存在跨系统时钟漂移；给宽限只会让「过期」这件事变得不确定，
 * 而客户端收到 {@code 40103 TOKEN_EXPIRED} 的处理路径本来就存在（刷新后重连）。
 *
 * <p>本类是<b>纯函数式</b>的：时钟可注入，不碰数据库，因此边界条件
 * （过期、篡改、alg 伪装、字段缺失）都能用固定时钟精确命中。
 */
public final class JwtTokenService {

    /** header 固定为这一串：本系统只签发这一种 header，不做 JWS 全集支持。 */
    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    /** 密钥下限。HS256 的安全性上界就是密钥熵：短于 32 字节（256 位）即不达标。 */
    public static final int MIN_SECRET_BYTES = 32;

    /**
     * token 长度上限。
     *
     * <p>存在的意义是「在验签之前就把代价封顶」：解析一个 10MB 的 token 需要
     * 先 base64 解码再交给 Jackson 建树，这些工作都发生在任何身份判断之前。
     * 正常 token（含 handle 与几项声明）不超过 400 字符，4096 已是两个数量级的余量。
     */
    static final int MAX_TOKEN_CHARS = 4096;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final Clock clock;

    public JwtTokenService(String secret) {
        this(secret, Clock.systemUTC());
    }

    public JwtTokenService(String secret, Clock clock) {
        if (secret == null || secret.isBlank()) {
            // 不设默认值是有意的：默认值意味着「所有部署共用同一把钥匙」，
            // 泄露一次就等于所有环境都被伪造。宁可启动失败。
            throw new IllegalArgumentException(
                    "jwt secret 未配置：请设置 tm.identity.jwt-secret（环境变量 TM_JWT_SECRET）");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "jwt secret 过短：" + bytes.length + " 字节 < " + MIN_SECRET_BYTES
                            + " 字节。HS256 的强度上限就是密钥熵，短密钥可被离线爆破后伪造任意账号的 token");
        }
        this.secret = bytes;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * 签发。
     *
     * <p>声明项刻意精简：{@code sub}=actorId、{@code hdl}=handle、{@code atp}=actorType、
     * {@code iat}/{@code exp}。不放权限列表——权限每次由服务端从库里读，
     * 放进 token 意味着「改权限要等 token 过期才生效」，而 token 有效期是 2 小时。
     */
    public String issue(long actorId, String handle, ActorType actorType, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl 必须为正");
        }
        long now = clock.instant().getEpochSecond();
        long exp = now + ttl.toSeconds();
        StringBuilder payload = new StringBuilder(160);
        payload.append("{\"sub\":\"").append(actorId).append('"');
        if (handle != null) {
            // handle 限定为字母数字下划线（见 02-auth.md §2.1），无需转义；
            // 但仍用 Jackson 序列化以避免将来放开字符集时留下注入点。
            payload.append(",\"hdl\":").append(Json.write(handle));
        }
        payload.append(",\"atp\":").append(actorType == null ? ActorType.HUMAN.code() : actorType.code());
        payload.append(",\"iat\":").append(now);
        payload.append(",\"exp\":").append(exp);
        payload.append('}');

        String signingInput = b64(HEADER_JSON.getBytes(StandardCharsets.UTF_8))
                + '.' + b64(payload.toString().getBytes(StandardCharsets.UTF_8));
        return signingInput + '.' + b64(HmacSha256.mac(secret, signingInput.getBytes(StandardCharsets.US_ASCII)));
    }

    /**
     * 校验并解析。失败一律抛 {@link TmException}，错误码与 02-auth.md §4 的表一致：
     * 格式/签名问题 → {@code 40102}；过期 → {@code 40103}（retryable，客户端应刷新）。
     *
     * <p>签名错误归入「token 格式错误（40102）」而不是 40101：40101 专指
     * 「请求里没带 Authorization」，而长连接场景下压根没有请求头这个概念，
     * 把两者混在一起会让客户端不知道是该提示重新登录还是该检查拼装代码。
     */
    public TokenClaims verify(String token) {
        if (token == null || token.isBlank()) {
            throw new TmException(ErrorCode.INVALID_TOKEN_FORMAT, "token 为空");
        }
        if (token.length() > MAX_TOKEN_CHARS) {
            throw new TmException(ErrorCode.INVALID_TOKEN_FORMAT,
                    "token 超长 " + token.length() + " > " + MAX_TOKEN_CHARS);
        }
        int firstDot = token.indexOf('.');
        int lastDot = token.lastIndexOf('.');
        if (firstDot <= 0 || lastDot <= firstDot || lastDot == token.length() - 1) {
            throw new TmException(ErrorCode.INVALID_TOKEN_FORMAT, "不是三段式 JWT");
        }
        String headerPart = token.substring(0, firstDot);
        String payloadPart = token.substring(firstDot + 1, lastDot);
        String signaturePart = token.substring(lastDot + 1);

        // ① 先验签：在解析任何业务字段之前。
        //
        // 比的是 HMAC 的原始字节，不是它的十六进制表示——JWS 的第三段是
        // base64url(原始字节)。这里曾写成「把 hex 与签名段比」，
        // 结果是自签自验也永远对不上（而错误码指向 40102，看起来像客户端的问题）。
        String signingInput = headerPart + '.' + payloadPart;
        byte[] expectedSignature = HmacSha256.mac(secret, signingInput.getBytes(StandardCharsets.US_ASCII));
        byte[] givenSignature;
        try {
            givenSignature = DECODER.decode(signaturePart);
        } catch (IllegalArgumentException e) {
            throw new TmException(ErrorCode.INVALID_TOKEN_FORMAT, "签名段不是合法 base64url");
        }
        if (!HmacSha256.constantTimeEquals(expectedSignature, givenSignature)) {
            throw new TmException(ErrorCode.INVALID_TOKEN_FORMAT, "签名不匹配");
        }

        // ② 验签通过后才解析 header。
        JsonNode header = parse(headerPart, "header");
        JsonNode alg = header.get("alg");
        if (alg == null || !"HS256".equals(alg.asText())) {
            // 不走「按 alg 选算法」：这里只是比对。alg=none 或 RS256 都到此为止。
            throw new TmException(ErrorCode.INVALID_TOKEN_FORMAT,
                    "alg 不受支持: " + (alg == null ? "<缺失>" : alg.asText()));
        }

        // ③ 解析 payload 并校验时间与必需字段。
        JsonNode payload = parse(payloadPart, "payload");
        long issuedAt = requireLong(payload, "iat");
        long expiresAt = requireLong(payload, "exp");

        long now = clock.instant().getEpochSecond();
        if (now >= expiresAt) {
            throw new TmException(ErrorCode.TOKEN_EXPIRED,
                    "已于 " + Instant.ofEpochSecond(expiresAt) + " 过期（现在 " + Instant.ofEpochSecond(now) + "）");
        }

        long actorId = requireActorId(payload, "sub");
        if (actorId <= 0) {
            throw new TmException(ErrorCode.INVALID_TOKEN_FORMAT, "sub 非法: " + actorId);
        }
        String handle = payload.hasNonNull("hdl") ? payload.get("hdl").asText() : null;
        int typeCode = payload.hasNonNull("atp") ? payload.get("atp").asInt() : ActorType.HUMAN.code();
        ActorType actorType = ActorType.of(typeCode);
        if (actorType == null) {
            throw new TmException(ErrorCode.INVALID_TOKEN_FORMAT, "atp 非法: " + typeCode);
        }
        return new TokenClaims(actorId, handle, actorType,
                Instant.ofEpochSecond(issuedAt), Instant.ofEpochSecond(expiresAt));
    }

    private JsonNode parse(String base64UrlPart, String what) {
        byte[] raw;
        try {
            raw = DECODER.decode(base64UrlPart);
        } catch (IllegalArgumentException e) {
            throw new TmException(ErrorCode.INVALID_TOKEN_FORMAT, what + " 不是合法 base64url");
        }
        try {
            return Json.mapper().readTree(raw);
        } catch (Exception e) {
            throw new TmException(ErrorCode.INVALID_TOKEN_FORMAT, what + " 不是合法 JSON");
        }
    }

    private static long requireLong(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isNumber()) {
            // exp 缺失绝不能解释成「永不过期」：那会让一个漏写字段的签发 bug
            // 变成永久有效的 token。
            throw new TmException(ErrorCode.INVALID_TOKEN_FORMAT, "缺少或非法字段 " + field);
        }
        return v.asLong();
    }

    /**
     * 读 {@code sub}。
     *
     * <p>RFC 7519 把它定义为 StringOrURI，所以本系统<b>签发时写字符串</b>
     * （第三方库/网关校验我们的 token 时才不会踩坑）；
     * 而<b>读取时字符串与数字都接受</b>——「对外严格、对内宽容」是协议演进
     * 的常规姿态，早期版本签发过的数字形式 token 不应该在升级后立刻失效。
     */
    private static long requireActorId(JsonNode payload, String field) {
        JsonNode v = payload.get(field);
        if (v == null || v.isNull()) {
            throw new TmException(ErrorCode.INVALID_TOKEN_FORMAT, "缺少字段 " + field);
        }
        try {
            return v.isNumber() ? v.asLong() : Long.parseLong(v.asText().trim());
        } catch (NumberFormatException e) {
            throw new TmException(ErrorCode.INVALID_TOKEN_FORMAT, "字段 " + field + " 不是整数");
        }
    }

    private static String b64(byte[] raw) {
        return ENCODER.encodeToString(raw);
    }
}
