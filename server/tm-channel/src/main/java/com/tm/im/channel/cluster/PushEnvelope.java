package com.tm.im.channel.cluster;

import com.tm.im.common.json.Json;
import com.tm.im.proto.transport.Frame;

import java.util.Base64;
import java.util.Optional;

/**
 * 跨节点投递的载荷（{@code tm:push:{nodeId}} 频道上的一帧）。
 *
 * <p><b>为什么直接传「已经编好的整帧字节（base64）」而不是列字段</b>：
 * <ul>
 *   <li>接收方不需要再推导一遍。列字段的话，两边各写一遍「字段 → 帧」的映射，
 *       而漂移的表现是「本机推送正常、跨节点推送缺字段」——只有在多实例部署时才会出现；</li>
 *   <li>基帧的构造只发生一次（在发送方的 {@code TransportMessageMapper} 里），
 *       所以「客户端收到的那一帧」与「本机推送时收到的那一帧」逐字节相同。</li>
 * </ul>
 * 代价是 base64 把体积放大约 1/3，以及这一层不再是「可以直接看懂」的 JSON。
 * 前者在 Redis Pub/Sub 的量级上可以忽略（一条消息几百字节），后者用
 * {@link #frameBytes()} 换回来：{@code decode_wire.py} 能直接解析它。
 *
 * <p><b>为什么还要带 {@code actor_id}</b>：频道是按<b>节点</b>分的，
 * 一个频道上跑着该节点所有 Actor 的帧，接收方必须知道这一帧写给谁。
 *
 * <p>{@code from} 只用于日志与排障（「这帧是谁投过来的」）。它<b>不参与任何判断</b>：
 * 拿它做去重或路由都会引入一个「某个节点重启后 nodeId 不变」的隐蔽依赖。
 */
record PushEnvelope(long actorId, String from, String frame) {

    static PushEnvelope of(long actorId, String fromNodeId, Frame frame) {
        return new PushEnvelope(actorId, fromNodeId,
                Base64.getEncoder().encodeToString(frame.toByteArray()));
    }

    String toJson() {
        return Json.write(this);
    }

    /**
     * 解析。任何解析失败都返回空——<b>不抛异常</b>：频道上可能混进别的版本
     * （滚动升级期间新旧节点并存）或人手写进去的脏数据，而收到一条读不懂的消息
     * 的正确反应是跳过并计数，不是让监听线程死掉（那会让整个节点再也收不到推送）。
     */
    static Optional<PushEnvelope> parse(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            PushEnvelope envelope = Json.mapper().readValue(json, PushEnvelope.class);
            if (envelope.actorId() <= 0 || envelope.frame() == null || envelope.frame().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(envelope);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 还原成帧；内容坏了返回空（与 {@link #parse} 同一取舍）。 */
    Optional<Frame> frameBytes() {
        try {
            return Optional.of(Frame.parseFrom(Base64.getDecoder().decode(frame)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
