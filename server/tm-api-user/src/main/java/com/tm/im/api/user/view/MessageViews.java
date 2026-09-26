package com.tm.im.api.user.view;

import com.fasterxml.jackson.databind.JsonNode;
import com.tm.im.common.json.Json;
import com.tm.im.domain.entity.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 消息实体 → 对外视图的<b>唯一</b>转换点。
 *
 * <p>三件容易被分散到各处、然后各自漂移的事都在这里：
 * <ol>
 *   <li><b>时区换算</b>：{@code DATETIME(3)} 的含义由 {@code tm.time.zone} 定义，
 *       漏掉一次换算就会让时间差 8 小时（而客户端把它当成「消息顺序乱了」）；</li>
 *   <li><b>枚举用名字还是数字</b>：{@code msg_type} 用名字（见 {@link MessageView}）；</li>
 *   <li><b>content 的解析</b>：库里是 JSON 文本，对外必须是对象。</li>
 * </ol>
 *
 * <p><b>content 解析失败时的取舍</b>：给一个空对象 {@code {}} 并记 WARN，
 * 而不是让整个请求失败。库里存的是 {@code NOT NULL} 的 JSON 列，能解不出来的只可能是
 * 「有人绕过应用直接改了库」或「MySQL JSON 类型的新版本写入了应用读不懂的东西」——
 * 两者都是数据层面的历史遗留。此时让一页消息拉不开（用户看到「加载失败」）
 * 比显示一条内容为空的坏消息糟糕得多，而 WARN 日志里有它的 message_id 可以查。
 */
public final class MessageViews {

    private static final Logger log = LoggerFactory.getLogger(MessageViews.class);

    private MessageViews() {
    }

    public static MessageView toView(Message message, ZoneId databaseZone) {
        return new MessageView(
                id(message), convId(message), seq(message), senderId(message),
                msgType(message), content(message), message.getReplyTo(),
                instant(message.getCreatedAt(), databaseZone));
    }

    public static SendResultView toSendResult(Message message, ZoneId databaseZone) {
        return new SendResultView(
                id(message), convId(message), seq(message), message.getClientMsgId(), senderId(message),
                msgType(message), content(message), message.getReplyTo(),
                instant(message.getCreatedAt(), databaseZone));
    }

    public static List<MessageView> toViews(List<Message> messages, ZoneId databaseZone) {
        List<MessageView> out = new ArrayList<>(messages.size());
        for (Message message : messages) {
            out.add(toView(message, databaseZone));
        }
        return out;
    }

    // ------------------------------------------------------------------ 内部

    /**
     * {@code msg_type} 用枚举名（{@code "TEXT"}）。为 null 时返回 null 而不是空串：
     * 那条消息的 {@code msg_type} 列是 {@code NOT NULL}，所以这是一个「数据坏了」的信号，
     * 让它在响应里显式地是个 null，比伪装成一个空字符串更容易被发现。
     */
    private static String msgType(Message message) {
        return message.getMsgType() == null ? null : message.getMsgType().name();
    }

    private static JsonNode content(Message message) {
        String raw = message.getContent();
        if (raw == null || raw.isBlank()) {
            log.warn("消息 content 为空（库里是 NOT NULL，这不该发生）messageId={}", message.getId());
            return Json.mapper().createObjectNode();
        }
        try {
            JsonNode node = Json.mapper().readTree(raw);
            if (node == null || !node.isObject()) {
                log.warn("消息 content 不是 JSON 对象 messageId={} raw={}", message.getId(), abbreviate(raw));
                return Json.mapper().createObjectNode();
            }
            return node;
        } catch (Exception e) {
            log.warn("消息 content 解析失败 messageId={} raw={}", message.getId(), abbreviate(raw));
            return Json.mapper().createObjectNode();
        }
    }

    private static String abbreviate(String raw) {
        return raw.length() <= 120 ? raw : raw.substring(0, 120) + "...";
    }

    private static long id(Message message) {
        return message.getId() == null ? 0L : message.getId();
    }

    private static long convId(Message message) {
        return message.getConvId() == null ? 0L : message.getConvId();
    }

    private static long seq(Message message) {
        return message.getSeq() == null ? 0L : message.getSeq();
    }

    private static long senderId(Message message) {
        return message.getSenderId() == null ? 0L : message.getSenderId();
    }

    private static java.time.Instant instant(LocalDateTime time, ZoneId databaseZone) {
        return time == null ? null : time.atZone(databaseZone).toInstant();
    }
}
