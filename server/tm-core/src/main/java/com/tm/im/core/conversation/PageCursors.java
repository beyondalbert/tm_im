package com.tm.im.core.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.common.json.Json;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 分页游标的编解码 —— 文档里叫它 {@code opaque}（03-rest-api.md §1.5），
 * 但<b>「不透明」对客户端成立，对服务端不成立</b>：服务端必须能验证它。
 *
 * <p><b>为什么不用「base64(seq)」这种最小形式</b>：两类游标（会话列表按活跃时间、
 * 消息按 seq）一旦长得一样，把消息游标贴到会话列表上就不会报错，只会得到一个
 * 看起来正常但内容错了的列表——而客户端绝不会怀疑是游标贴错了。
 * 所以每个游标都带 {@code v}（版本）与 {@code t}（类型），解码时严格比对。
 *
 * <p><b>为什么不需要签名</b>：游标只表达「从<b>调用者自己的</b>列表的哪一项之后继续」，
 * 它不携带任何别人的数据，伪造它既不能越权也读不到别人的会话——
 * 最坏的结果是调用者自己的列表分页错乱。给它加 HMAC 只会引入一把要轮换的密钥，
 * 换不来任何安全性（这一点与 refresh_token 相反：那个是凭证，必须存哈希）。
 *
 * <p><b>时间用「毫秒时间戳」而不是 ISO 字符串</b>：游标要参与比较
 * （「比这一项更旧」），而字符串比较与时间比较只在格式完全一致时等价；
 * 毫秒整数则天然可比，且 {@code DATETIME(3)} 本身就是毫秒精度，不会因为
 * 纳秒被截断而让「游标指向的那一项」在两次请求里指向不同位置（那会让分页重复或漏项）。
 *
 * <p>解码失败一律 {@code 40010 INVALID_CURSOR}，并在 detail 里说清是坏在哪：
 * 客户端能自己修好这件事（丢掉本地游标重新拉），前提是它知道这不是「服务端坏了」。
 */
public final class PageCursors {

    /** 载荷版本。服务端改游标结构时递增，老游标会被判为「不认识」而不是被误解。 */
    private static final int VERSION = 1;

    private static final String TYPE_CONVERSATION = "conv";
    private static final String TYPE_MESSAGE = "msg";
    private static final String TYPE_FRIEND = "friend";
    private static final String TYPE_FRIEND_REQUEST = "freq";

    private PageCursors() {
    }

    /**
     * 会话列表游标。
     *
     * @param updatedAtMillis 上一页最后一项的「最近活跃时间」（Unix 毫秒）
     * @param convId           上一页最后一项的会话 id（时间相同时用它定序，见 {@link #encodeConversation}）
     */
    public record ConversationCursor(long updatedAtMillis, long convId) {
    }

    /** 消息游标：上一页最后一条的 {@code seq}。下一个请求返回 {@code seq < 它} 的消息。 */
    public record MessageCursor(long seq) {
    }

    /**
     * 社交类列表的游标（好友列表、好友请求列表）：{@code (updated_at, request_id)}。
     *
     * <p>两者形状相同但是<b>两个不同的类型</b>（{@code friend} 与 {@code freq}）：
     * 它们来自两张不同的语义（「谁是好友」与「有哪些请求」），长得一样时贴错不会报错，
     * 只会返回一个内容不对的列表——而客户端绝不会怀疑是游标贴错了。
     * 这条取舍与会话/消息两种游标同源（见类注释）。
     *
     * <p>第二个字段是 {@code request_id} 而不是「对方的 actor_id」：
     * 关系存的是无序对，对方可能在 {@code actor_a} 也可能在 {@code actor_b}，
     * 而 {@code request_id} 是每一行自己的列，两个方向用的是同一个谓词（见仓储注释）。
     */
    public record SocialCursor(long updatedAtMillis, long requestId) {
    }

    /** 编码好友列表游标。 */
    public static String encodeFriend(long updatedAtMillis, long requestId) {
        return encodeSocial(TYPE_FRIEND, updatedAtMillis, requestId);
    }

    /** 编码好友请求列表游标。 */
    public static String encodeFriendRequest(long updatedAtMillis, long requestId) {
        return encodeSocial(TYPE_FRIEND_REQUEST, updatedAtMillis, requestId);
    }

    public static SocialCursor decodeFriend(String cursor) {
        return decodeSocial(parse(cursor, TYPE_FRIEND), cursor);
    }

    public static SocialCursor decodeFriendRequest(String cursor) {
        return decodeSocial(parse(cursor, TYPE_FRIEND_REQUEST), cursor);
    }

    private static String encodeSocial(String type, long updatedAtMillis, long requestId) {
        return encode("{\"v\":" + VERSION + ",\"t\":\"" + type + "\""
                + ",\"at\":" + updatedAtMillis + ",\"rid\":" + requestId + "}");
    }

    private static SocialCursor decodeSocial(JsonNode node, String cursor) {
        long at = requireLong(node, "at", cursor);
        long rid = requireLong(node, "rid", cursor);
        if (at < 0) {
            throw invalid(cursor, "at（更新时间）为负");
        }
        if (rid <= 0) {
            throw invalid(cursor, "rid（请求 id）必须为正整数");
        }
        return new SocialCursor(at, rid);
    }

    /**
     * 编码会话游标。
     *
     * <p>{@code convId} 必须一起带上：活跃时间可能相同（批量导入的消息、
     * 同一毫秒内建的两个会话），只带时间的话下一页的起点会落在「时间相同的那一批」
     * 中间，而服务端无法知道该从谁开始——结果是要么重复下发一批，要么跳过一批。
     * 带上 id 之后，排序键与游标键是同一对值，分页就不可能出现两者都不满足的缝隙。
     */
    public static String encodeConversation(long updatedAtMillis, long convId) {
        return encode("{\"v\":" + VERSION + ",\"t\":\"" + TYPE_CONVERSATION + "\""
                + ",\"at\":" + updatedAtMillis + ",\"cid\":" + convId + "}");
    }

    /** 编码消息游标。 */
    public static String encodeMessage(long seq) {
        return encode("{\"v\":" + VERSION + ",\"t\":\"" + TYPE_MESSAGE + "\",\"seq\":" + seq + "}");
    }

    public static ConversationCursor decodeConversation(String cursor) {
        JsonNode node = parse(cursor, TYPE_CONVERSATION);
        long at = requireLong(node, "at", cursor);
        long cid = requireLong(node, "cid", cursor);
        if (at < 0) {
            throw invalid(cursor, "at（活跃时间）为负");
        }
        if (cid <= 0) {
            throw invalid(cursor, "cid（会话 id）必须为正整数");
        }
        return new ConversationCursor(at, cid);
    }

    public static MessageCursor decodeMessage(String cursor) {
        JsonNode node = parse(cursor, TYPE_MESSAGE);
        long seq = requireLong(node, "seq", cursor);
        if (seq <= 0) {
            // seq 从 1 开始（取号即消耗，见 DESIGN §10.1），所以 0 与负数都不是有效游标。
            // 这里不能「宽容地当成 0」：那会把一个坏游标变成一个「从头拉一遍」的语义，
            // 而客户端会以为自己在续传。
            throw invalid(cursor, "seq 必须为正整数");
        }
        return new MessageCursor(seq);
    }

    // ------------------------------------------------------------------ 内部

    private static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode parse(String cursor, String expectedType) {
        if (cursor == null || cursor.isBlank()) {
            throw invalid(cursor, "空的游标");
        }
        byte[] raw;
        try {
            // 用 MIME 解码器：它容忍补位的 '='、换行与 URL 安全字符集，
            // 而客户端把游标放进 JSON 再读出来时，这些变形都会出现。
            raw = Base64.getMimeDecoder().decode(cursor);
        } catch (IllegalArgumentException e) {
            throw invalid(cursor, "不是合法的 base64");
        }
        JsonNode node;
        try {
            node = Json.mapper().readTree(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw invalid(cursor, "解出的内容不是 JSON");
        }
        if (node == null || !node.isObject()) {
            throw invalid(cursor, "解出的内容不是 JSON 对象");
        }
        JsonNode version = node.get("v");
        if (version == null || !version.isNumber() || version.asInt() != VERSION) {
            throw invalid(cursor, "版本不认识（服务端期望 v=" + VERSION + "）");
        }
        JsonNode type = node.get("t");
        if (type == null || !type.isTextual() || !expectedType.equals(type.asText())) {
            throw invalid(cursor, "类型不匹配：本接口要 " + expectedType + " 游标");
        }
        return node;
    }

    private static long requireLong(JsonNode node, String field, String cursor) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw invalid(cursor, "缺少数字字段 " + field);
        }
        return value.asLong();
    }

    private static TmException invalid(String cursor, String why) {
        // detail 里带上原值（截断），因为「哪个客户端把游标搞坏了」是最常见的问题，
        // 而游标是不含敏感信息的（见类注释），可以直接进日志。
        String shown = cursor == null ? "<null>"
                : cursor.length() <= 64 ? cursor : cursor.substring(0, 64) + "...";
        return new TmException(ErrorCode.INVALID_CURSOR, why + "，cursor=" + shown);
    }
}
