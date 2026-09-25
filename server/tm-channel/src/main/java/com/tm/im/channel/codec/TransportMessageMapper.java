package com.tm.im.channel.codec;

import com.tm.im.domain.entity.Message;
import com.tm.im.domain.enums.MessageType;
import com.tm.im.proto.transport.MsgType;
import com.tm.im.proto.transport.PushMessage;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 领域对象 → 传输帧的转换。这是 tm-core 与 tm-channel 之间<b>唯一</b>的语义转换点。
 *
 * <p>两端刻意用不同的类型：领域侧是 {@code com.tm.im.domain.entity.Message}，
 * 传输侧是 protobuf 生成的 {@code Message}。让领域层直接持有 protobuf 对象
 * （或反过来）会省掉这一层，但代价是「改线格式」与「改业务模型」从此互相牵制。
 *
 * <p>转换里有两处必须显式写出来、不能靠「数字刚好一样」的地方：
 * <ol>
 *   <li><b>枚举映射</b>：领域 {@code MessageType}（1/2/3）与协议 {@code MsgType}
 *       （1/2/3）当前取值相同，但这是巧合而不是契约。这里用显式 switch +
 *       未知值抛异常：将来领域侧新增一个类型而协议没加，
 *       应当在发送时就炸掉，而不是静默地发出一帧 {@code MSG_TYPE_UNKNOWN}，
 *       让客户端显示「未知消息类型」却没人知道是哪条链路漏了映射。</li>
 *   <li><b>时间口径</b>：{@code LocalDateTime} 无时区，必须用配置的
 *       {@code tm.time.zone} 解释（见 {@code TimeProperties}）。</li>
 * </ol>
 *
 * <p><b>为什么叫 Transport 而不是更短的 {@code MessageMapper}</b>：
 * 那个名字已经被 {@code com.tm.im.storage.mapper.MessageMapper}（MyBatis 的
 * 分片消息表映射器）占了，而 Spring 容器里的 Bean 名默认就是类的短名 ——
 * 两者撞在一起时应用<b>启动直接失败</b>：
 * <pre>
 * ConflictingBeanDefinitionException: Annotation-specified bean name 'messageMapper'
 *    for bean class [com.tm.im.storage.mapper.MessageMapper] conflicts with existing,
 *    non-compatible bean definition of same name and class [com.tm.im.channel.codec.MessageMapper]
 * </pre>
 * 这个缺陷在任何测试自己的容器里都碰不到（{@code ItSpringConfig} 只扫
 * {@code com.tm.im.storage.repository}，{@code MockMvc} 根本不建容器），
 * 它由 {@code tm-app} 的启动集成测试 {@code AppHttpIT} 抓到。
 * 想知道「为什么不叫 MessageMapper」，看这一段就够了。
 */
@Component
public class TransportMessageMapper {

    private final ZoneId zone;

    public TransportMessageMapper(ZoneId zone) {
        this.zone = zone;
    }

    /** 组装 {@code CMD_PUSH} 帧。服务端主动推送的 {@code req_id} 恒为 0（04-realtime.md §2.2）。 */
    public com.tm.im.proto.transport.Frame pushFrame(Message message) {
        return Frames.of(com.tm.im.proto.transport.Frame.Cmd.CMD_PUSH, 0,
                PushMessage.newBuilder().setMessage(toTransport(message)).build());
    }

    public com.tm.im.proto.transport.Message toTransport(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("message 不能为 null");
        }
        require(message.getConvId(), "conv_id");
        require(message.getSeq(), "seq");
        require(message.getSenderId(), "sender_id");

        com.tm.im.proto.transport.Message.Builder builder = com.tm.im.proto.transport.Message.newBuilder()
                .setMessageId(nullToZero(message.getId()))
                .setConvId(message.getConvId())
                .setSeq(message.getSeq())
                .setSenderId(message.getSenderId())
                .setMsgType(toTransport(message.getMsgType()))
                .setContentJson(message.getContent() == null ? "" : message.getContent())
                .setReplyTo(nullToZero(message.getReplyTo()))
                .setCreatedAtMs(toEpochMillis(message.getCreatedAt()));
        return builder.build();
    }

    static MsgType toTransport(MessageType type) {
        if (type == null) {
            return MsgType.MSG_TYPE_UNKNOWN;
        }
        return switch (type) {
            case TEXT -> MsgType.MSG_TYPE_TEXT;
            case IMAGE -> MsgType.MSG_TYPE_IMAGE;
            case SYSTEM -> MsgType.MSG_TYPE_SYSTEM;
        };
    }

    /**
     * {@code created_at} 转 Unix 毫秒。
     *
     * <p>缺失时返回 0 而不是当前时间：客户端据此可以区分「服务端没给时间」
     * 与「服务端给了时间」。伪造一个「现在」会让客户端以为时间可靠，
     * 而真实时间可能在几小时前。
     */
    long toEpochMillis(LocalDateTime createdAt) {
        return createdAt == null ? 0L : createdAt.atZone(zone).toInstant().toEpochMilli();
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private static void require(Long value, String field) {
        if (value == null || value == 0L) {
            throw new IllegalArgumentException(
                    "推送的消息缺少 " + field + "：分片键/排序键/sender 任一为空，客户端都无法正确处理");
        }
    }
}
