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

    /** 好友段的信息流游标（{@code feed_item.score} + {@code post_id}）。 */
    public static final String TYPE_PLAZA_FEED = "pfeed";

    /** 公开流段的游标（{@code post.created_at} + {@code post.id}）。 */
    public static final String TYPE_PLAZA_PUBLIC = "ppub";

    /** §6.3「某人的动态」的游标。与公开流形状相同，但**刻意是另一个类型**。 */
    private static final String TYPE_PLAZA_POSTS = "ppost";

    /** §6.6 评论列表的游标。 */
    private static final String TYPE_PLAZA_COMMENT = "pcmt";

    // -------- 管理后台（M9）：四个列表都只带一个 id --------
    //
    // 它们的形状完全一样，照理可以只用一个类型——但那正是本类开头说的那个坑：
    // 把「审计日志的第二页」的游标贴到「用户列表」上不会报错，只会返回一个
    // 内容不对的列表。四个类型标签的代价是四行常量，换来的是贴错就报 40010。
    private static final String TYPE_ADMIN_ACTOR = "aact";
    private static final String TYPE_ADMIN_POST = "apst";
    private static final String TYPE_ADMIN_AUDIT = "alog";
    private static final String TYPE_ADMIN_ACCOUNT = "aadm";

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

    // ------------------------------------------------------------------ 广场（§6）

    /**
     * 游标的类型标签（{@code t}），用于「多段游标」的接口先分派再解码。
     *
     * <p>返回值 {@code null} 表示这个字符串根本不是「base64 包着的 JSON 对象」——
     * 调用方应当把它交给对应的 {@code decodeXxx} 去抛 {@code 40010}
     * （那是唯一会带上原值与具体原因的地方）。
     *
     * <p>为什么需要它：信息流的两段各有自己的游标形状，而「读哪一段」必须
     * 在解码之前就知道。让调用方自己 base64 解一遍等于把格式解析写两遍。
     */
    public static String typeOf(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            byte[] raw = Base64.getMimeDecoder().decode(cursor);
            JsonNode node = Json.mapper().readTree(new String(raw, StandardCharsets.UTF_8));
            if (node == null || !node.isObject() || !node.path("v").isNumber()
                    || node.path("v").asInt() != VERSION) {
                return null;
            }
            JsonNode type = node.get("t");
            return type == null || !type.isTextual() ? null : type.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 信息流<b>好友段</b>的游标：{@code (score, post_id)}。
     *
     * <p>直接携带 {@code feed_item.score} 而不是「时间戳」：score 是主键的一部分，
     * 而把 score 反解回时间再比较会丢掉那 20 位 tieBreaker，
     * 于是同一秒内的多条动态在翻页时表现成「重复/漏项」（见 {@link FeedScores} 的注释）。
     */
    public record PlazaFeedCursor(long score, long postId) {
    }

    /**
     * 信息流<b>公开流段</b>的游标：{@code (created_at 毫秒, post_id)}。
     *
     * <p>两个字段<b>都为 null</b> 是一种合法取值，含义是「从最新一条开始」。
     * 它不是冗余设计：好友段读完之后要继续往公开流里填，而当好友段恰好填满一页时，
     * 服务端需要给客户端一个「公开流还没开始」的游标——那个位置不存在具体的行，
     * 所以它只能被显式表达。用哨兵值（如 {@code Long.MAX_VALUE}）代替会有一个
     * 隐藏的失败面：哨兵落在合法取值区间里，客户端只要从别处抄一个时间戳就能撞上它。
     */
    public record PlazaPublicCursor(Long atMillis, Long postId) {
    }

    /** §6.3「某人的动态」的游标：{@code (created_at 毫秒, post_id)}。 */
    public record PlazaPostsCursor(long atMillis, long postId) {
    }

    /** §6.6 评论列表的游标：{@code (created_at 毫秒, comment_id)}。 */
    public record PlazaCommentCursor(long atMillis, long commentId) {
    }

    public static String encodePlazaFeed(long score, long postId) {
        return encode("{\"v\":" + VERSION + ",\"t\":\"" + TYPE_PLAZA_FEED
                + "\",\"sc\":" + score + ",\"pid\":" + postId + "}");
    }

    /** 公开流的「起始位置」游标（好友段刚读完、还没取过公开流里的任何一条）。 */
    public static String encodePlazaPublicStart() {
        return encode("{\"v\":" + VERSION + ",\"t\":\"" + TYPE_PLAZA_PUBLIC + "\"}");
    }

    public static String encodePlazaPublic(long atMillis, long postId) {
        return encode("{\"v\":" + VERSION + ",\"t\":\"" + TYPE_PLAZA_PUBLIC
                + "\",\"at\":" + atMillis + ",\"pid\":" + postId + "}");
    }

    public static String encodePlazaPosts(long atMillis, long postId) {
        return encode("{\"v\":" + VERSION + ",\"t\":\"" + TYPE_PLAZA_POSTS
                + "\",\"at\":" + atMillis + ",\"pid\":" + postId + "}");
    }

    public static String encodePlazaComment(long atMillis, long commentId) {
        return encode("{\"v\":" + VERSION + ",\"t\":\"" + TYPE_PLAZA_COMMENT
                + "\",\"at\":" + atMillis + ",\"cid\":" + commentId + "}");
    }

    public static PlazaFeedCursor decodePlazaFeed(String cursor) {
        JsonNode node = parse(cursor, TYPE_PLAZA_FEED);
        long score = requireLong(node, "sc", cursor);
        long postId = requireLong(node, "pid", cursor);
        if (score < 0) {
            throw invalid(cursor, "sc（排序分）为负");
        }
        if (postId <= 0) {
            throw invalid(cursor, "pid（动态 id）必须为正整数");
        }
        return new PlazaFeedCursor(score, postId);
    }

    public static PlazaPublicCursor decodePlazaPublic(String cursor) {
        JsonNode node = parse(cursor, TYPE_PLAZA_PUBLIC);
        return new PlazaPublicCursor(optionalLong(node, "at"), optionalLong(node, "pid"));
    }

    public static PlazaPostsCursor decodePlazaPosts(String cursor) {
        JsonNode node = parse(cursor, TYPE_PLAZA_POSTS);
        long at = requireLong(node, "at", cursor);
        long postId = requireLong(node, "pid", cursor);
        if (at < 0) {
            throw invalid(cursor, "at（时间）为负");
        }
        if (postId <= 0) {
            throw invalid(cursor, "pid（动态 id）必须为正整数");
        }
        return new PlazaPostsCursor(at, postId);
    }

    public static PlazaCommentCursor decodePlazaComment(String cursor) {
        JsonNode node = parse(cursor, TYPE_PLAZA_COMMENT);
        long at = requireLong(node, "at", cursor);
        long cid = requireLong(node, "cid", cursor);
        if (at < 0) {
            throw invalid(cursor, "at（时间）为负");
        }
        if (cid <= 0) {
            throw invalid(cursor, "cid（评论 id）必须为正整数");
        }
        return new PlazaCommentCursor(at, cid);
    }

    // ------------------------------------------------------------------ 管理后台（M9）

    /**
     * 后台列表的游标：只有一个 {@code id}。
     *
     * <p><b>为什么不需要像信息流那样带一对 {@code (时间, id)}</b>：后台列表按
     * {@code id} 倒序，而 {@code id} 是 Snowflake——单调递增且与创建时间同序。
     * 于是「比游标更旧的项」就是一个严格的 {@code id < ?}，没有同毫秒并列的缝隙，
     * 也不会出现「翻页时插入新行导致重复/漏项」（新行的 id 更大，落在第一页那侧）。
     */
    public record AdminCursor(long id) {
    }

    public static String encodeAdminActor(long id) {
        return encode(adminPayload(TYPE_ADMIN_ACTOR, id));
    }

    public static String encodeAdminPost(long id) {
        return encode(adminPayload(TYPE_ADMIN_POST, id));
    }

    public static String encodeAdminAudit(long id) {
        return encode(adminPayload(TYPE_ADMIN_AUDIT, id));
    }

    public static String encodeAdminAccount(long id) {
        return encode(adminPayload(TYPE_ADMIN_ACCOUNT, id));
    }

    public static AdminCursor decodeAdminActor(String cursor) {
        return decodeAdmin(cursor, TYPE_ADMIN_ACTOR);
    }

    public static AdminCursor decodeAdminPost(String cursor) {
        return decodeAdmin(cursor, TYPE_ADMIN_POST);
    }

    public static AdminCursor decodeAdminAudit(String cursor) {
        return decodeAdmin(cursor, TYPE_ADMIN_AUDIT);
    }

    public static AdminCursor decodeAdminAccount(String cursor) {
        return decodeAdmin(cursor, TYPE_ADMIN_ACCOUNT);
    }

    private static String adminPayload(String type, long id) {
        return "{\"v\":" + VERSION + ",\"t\":\"" + type + "\",\"id\":" + id + "}";
    }

    private static AdminCursor decodeAdmin(String cursor, String type) {
        JsonNode node = parse(cursor, type);
        long id = requireLong(node, "id", cursor);
        if (id <= 0) {
            throw invalid(cursor, "id 必须为正整数");
        }
        return new AdminCursor(id);
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

    /** 可选数字字段：缺席或 null 都返回 null（见 {@link #encodePlazaPublicStart}）。 */
    private static Long optionalLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isNumber() ? null : value.asLong();
    }

    private static TmException invalid(String cursor, String why) {
        // detail 里带上原值（截断），因为「哪个客户端把游标搞坏了」是最常见的问题，
        // 而游标是不含敏感信息的（见类注释），可以直接进日志。
        String shown = cursor == null ? "<null>"
                : cursor.length() <= 64 ? cursor : cursor.substring(0, 64) + "...";
        return new TmException(ErrorCode.INVALID_CURSOR, why + "，cursor=" + shown);
    }
}
