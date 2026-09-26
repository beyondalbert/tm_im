package com.tm.im.channel.cluster;

import com.tm.im.channel.codec.Frames;
import com.tm.im.common.json.Json;
import com.tm.im.proto.transport.Frame;
import com.tm.im.proto.transport.PushMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨节点投递载荷的编解码（{@code tm:push:{nodeId}} 频道上的内容）。
 *
 * <p>它值得单独测的理由：这是<b>两个进程之间的约定</b>（可能是两个版本），
 * 而且失败方式很隐蔽——解析不出来的一帧会被跳过并计数，
 * 表现是「跨节点推送偶尔不生效」（客户端靠 SYNC 补上了，没人会发现）。
 * 所以「能不能解回来」和「解不回来时会不会抛」都要钉住。
 */
class PushEnvelopeTest {

    @Test
    @DisplayName("往返：帧逐字节还原，actorId 与来源节点都在")
    void roundTripKeepsFrameBytes() {
        Frame frame = Frames.of(Frame.Cmd.CMD_PUSH, 0, PushMessage.newBuilder().build());
        PushEnvelope envelope = PushEnvelope.of(1001L, "node-a", frame);

        Optional<PushEnvelope> parsed = PushEnvelope.parse(envelope.toJson());

        assertThat(parsed).isPresent();
        assertThat(parsed.get().actorId()).isEqualTo(1001L);
        assertThat(parsed.get().from()).isEqualTo("node-a");
        assertThat(parsed.get().frameBytes()).contains(frame);
        assertThat(parsed.get().frameBytes().orElseThrow().toByteArray())
                .as("接收方写进 Channel 的就是发送方编出来的那一帧")
                .isEqualTo(frame.toByteArray());
    }

    @Test
    @DisplayName("解析失败一律返回空，不抛异常（频道上可能有别的版本或脏数据）")
    void unparsablePayloadsAreEmpty() {
        assertThat(PushEnvelope.parse(null)).isEmpty();
        assertThat(PushEnvelope.parse("")).isEmpty();
        assertThat(PushEnvelope.parse("   ")).isEmpty();
        assertThat(PushEnvelope.parse("{ not json")).isEmpty();
        assertThat(PushEnvelope.parse("{\"actorId\":0,\"frame\":\"x\"}"))
                .as("actorId 非正数时按无效处理")
                .isEmpty();
        assertThat(PushEnvelope.parse("{\"actorId\":1}")).as("缺 frame").isEmpty();
        assertThat(PushEnvelope.parse("{\"actorId\":1,\"frame\":\"\"}")).isEmpty();
    }

    @Test
    @DisplayName("载荷是 base64 的 JSON：体积可控且能一眼看出是哪个节点投的")
    void payloadShape() {
        Frame frame = Frames.of(Frame.Cmd.CMD_PUSH, 0, PushMessage.newBuilder().build());
        String json = PushEnvelope.of(1001L, "node-a", frame).toJson();

        // 用 Json 自己的键名（camelCase）：两端共用同一个 ObjectMapper，
        // 而对外接口那套 snake_case 是 Spring 那边的配置，与这里无关。
        assertThat(json).contains("\"actorId\":1001").contains("\"from\":\"node-a\"")
                .contains("\"frame\":\"");
        assertThat(Json.read(json, com.fasterxml.jackson.databind.JsonNode.class)
                .get("frame").asText())
                .as("frame 是 base64 而不是可读 JSON —— 代价换的是「两边不必各推一遍字段映射」")
                .matches("[A-Za-z0-9+/=]+");
    }
}
