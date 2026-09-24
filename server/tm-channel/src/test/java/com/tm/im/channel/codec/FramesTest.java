package com.tm.im.channel.codec;

import com.google.protobuf.ByteString;
import com.tm.im.proto.transport.AuthRequest;
import com.tm.im.proto.transport.Frame;
import com.tm.im.proto.transport.Message;
import com.tm.im.proto.transport.MsgType;
import com.tm.im.proto.transport.PushMessage;
import com.tm.im.proto.transport.SendAck;
import com.tm.im.proto.transport.SendRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 与 <b>docs/integration/04-realtime.md §4 的「实测字节」逐字节对齐</b>。
 *
 * <p>这是本项目里最有价值的一类测试：那份文档是给第三方开发者照着实现客户端的
 * 契约，文档里的 hex 是他们自测的唯一依据。如果服务端编解码与文档不一致，
 * 用其他语言写的 SDK 会「按文档实现却连不上」，而这种问题排查起来极其昂贵
 * （双方都确信自己是对的，且每一端单独看都合理）。
 *
 * <p>因此这里做<b>双向</b>断言：
 * <ul>
 *   <li>解码方向：文档里的字节 → 服务端解析出预期字段；</li>
 *   <li>编码方向：服务端构造同样的字段 → 产出的字节与文档<b>完全相同</b>。</li>
 * </ul>
 * 只有第二个方向能发现「字段值都对但编码方式不同」的情况
 * （例如把 uint64 写成定长、或字段顺序被改动）。
 *
 * <p>文档侧还有对应的 Python 校验（{@code tools/verify_integration_docs.py}），
 * 但它校验的是「文档内部自洽 + 手写实现」，管不到真正跑在服务端的这份代码。
 */
class FramesTest {

    private static final String AUTH_FRAME_HEX = """
            08 01 10 01 1a 31 0a 18 73 6b 5f 6c 69 76 65 5f
            39 66 32 63 31 64 37 61 34 62 38 65 33 66 36 30
            12 05 31 2e 30 2e 30 1a 0e 77 65 62 2d 63 68 72
            6f 6d 65 2d 31 33 31""";

    private static final String SEND_FRAME_HEX = """
            08 0a 10 02 1a 2c 08 e9 07 12 0a 63 2d 37 66 33
            61 39 62 32 31 18 01 22 19 7b 22 74 65 78 74 22
            3a 22 e4 bd a0 e5 a5 bd ef bc 8c 41 67 65 6e 74
            22 7d""";

    private static final String SEND_ACK_FRAME_HEX = """
            08 0b 10 02 1a 22 08 e9 07 10 07 18 81 80 a4 f0
            9d e4 de 90 0a 20 fb d0 ea b6 b7 33 2a 0a 63 2d
            37 66 33 61 39 62 32 31""";

    private static final String PUSH_FRAME_HEX = """
            08 0c 1a 42 0a 40 08 82 80 a4 f0 9d e4 de 90 0a
            10 e9 07 18 08 20 d2 0f 28 01 32 23 7b 22 74 65
            78 74 22 3a 22 e6 94 b6 e5 88 b0 ef bc 8c e4 bb
            8a e5 a4 a9 e5 8c 97 e4 ba ac e6 99 b4 22 7d 40
            c8 d3 ea b6 b7 33""";

    private static byte[] hex(String block) {
        return HexFormat.of().parseHex(block.replaceAll("\\s+", ""));
    }

    @Test
    @DisplayName("AUTH 帧：文档的 55 字节可解、且服务端能原样产出这些字节")
    void authFrameMatchesDoc() {
        byte[] documented = hex(AUTH_FRAME_HEX);
        assertThat(documented).hasSize(55);

        Frame decoded = parse(documented);
        assertThat(decoded.getCmd()).isEqualTo(Frame.Cmd.CMD_AUTH);
        assertThat(decoded.getReqId()).isEqualTo(1L);
        assertThat(decoded.getPayload().size()).isEqualTo(49);

        AuthRequest request = Frames.body(decoded, AuthRequest.getDefaultInstance());
        assertThat(request.getToken()).isEqualTo("sk_live_9f2c1d7a4b8e3f60");
        assertThat(request.getClientVersion()).isEqualTo("1.0.0");
        assertThat(request.getDeviceId()).isEqualTo("web-chrome-131");

        // 编码方向：同样的字段必须得到同样的字节
        Frame rebuilt = Frames.of(Frame.Cmd.CMD_AUTH, 1, AuthRequest.newBuilder()
                .setToken("sk_live_9f2c1d7a4b8e3f60")
                .setClientVersion("1.0.0")
                .setDeviceId("web-chrome-131")
                .build());
        assertThat(rebuilt.toByteArray()).isEqualTo(documented);
    }

    @Test
    @DisplayName("SEND 帧：50 字节；中文 content 的 LEN 是字节数（25）而不是字符数")
    void sendFrameMatchesDoc() {
        byte[] documented = hex(SEND_FRAME_HEX);
        assertThat(documented).hasSize(50);

        Frame decoded = parse(documented);
        assertThat(decoded.getCmd()).isEqualTo(Frame.Cmd.CMD_SEND);
        assertThat(decoded.getReqId()).isEqualTo(2L);
        assertThat(decoded.getPayload().size()).isEqualTo(44);

        SendRequest send = Frames.body(decoded, SendRequest.getDefaultInstance());
        assertThat(send.getConvId()).isEqualTo(1001L);
        assertThat(send.getClientMsgId()).isEqualTo("c-7f3a9b21");
        assertThat(send.getMsgType()).isEqualTo(MsgType.MSG_TYPE_TEXT);
        assertThat(send.getContentJson()).isEqualTo("{\"text\":\"你好，Agent\"}");
        // 「你好，Agent」= 3+3+3+5 = 14 字节，加上 {"text":"…"} 的 11 字节结构 = 25
        assertThat(send.getContentJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(25);

        Frame rebuilt = Frames.of(Frame.Cmd.CMD_SEND, 2, SendRequest.newBuilder()
                .setConvId(1001)
                .setClientMsgId("c-7f3a9b21")
                .setMsgType(MsgType.MSG_TYPE_TEXT)
                .setContentJson("{\"text\":\"你好，Agent\"}")
                .build());
        assertThat(rebuilt.toByteArray()).isEqualTo(documented);
    }

    @Test
    @DisplayName("SEND_ACK 帧：40 字节；message_id 用 varint 是 9 字节（比定长还多 1 字节，但这就是 protobuf 的默认行为）")
    void sendAckFrameMatchesDoc() {
        byte[] documented = hex(SEND_ACK_FRAME_HEX);
        assertThat(documented).hasSize(40);

        Frame decoded = parse(documented);
        assertThat(decoded.getCmd()).isEqualTo(Frame.Cmd.CMD_SEND_ACK);
        // req_id 与 SEND 帧相同 —— 这是客户端配对响应的唯一依据
        assertThat(decoded.getReqId()).isEqualTo(2L);

        SendAck ack = Frames.body(decoded, SendAck.getDefaultInstance());
        assertThat(ack.getConvId()).isEqualTo(1001L);
        assertThat(ack.getSeq()).isEqualTo(7L);
        assertThat(ack.getMessageId()).isEqualTo(730000000000000001L);
        assertThat(ack.getCreatedAtMs()).isEqualTo(1767225600123L);
        assertThat(ack.getClientMsgId()).isEqualTo("c-7f3a9b21");

        Frame rebuilt = Frames.of(Frame.Cmd.CMD_SEND_ACK, 2, SendAck.newBuilder()
                .setConvId(1001)
                .setSeq(7)
                .setMessageId(730000000000000001L)
                .setCreatedAtMs(1767225600123L)
                .setClientMsgId("c-7f3a9b21")
                .build());
        assertThat(rebuilt.toByteArray()).isEqualTo(documented);
    }

    @Test
    @DisplayName("PUSH 帧：req_id=0 时该字段完全不出现在字节里（protobuf3 默认值不编码）")
    void pushFrameOmitsZeroReqId() {
        byte[] documented = hex(PUSH_FRAME_HEX);
        assertThat(documented).hasSize(70);

        Frame decoded = parse(documented);
        assertThat(decoded.getCmd()).isEqualTo(Frame.Cmd.CMD_PUSH);
        assertThat(decoded.getReqId()).isZero();
        // 字节开头是 08 0c（字段1=cmd=12），紧接着就是 1a（字段3 的 tag）。
        // 若 req_id 被编码，中间会出现 10（字段2 的 tag）—— 它缺席正是本节的重点。
        assertThat(documented[0]).isEqualTo((byte) 0x08);
        assertThat(documented[1]).isEqualTo((byte) 0x0C);
        assertThat(documented[2]).isEqualTo((byte) 0x1A);

        PushMessage push = Frames.body(decoded, PushMessage.getDefaultInstance());
        Message message = push.getMessage();
        assertThat(message.getMessageId()).isEqualTo(730000000000000002L);
        assertThat(message.getConvId()).isEqualTo(1001L);
        assertThat(message.getSeq()).isEqualTo(8L);
        assertThat(message.getSenderId()).isEqualTo(2002L);
        assertThat(message.getMsgType()).isEqualTo(MsgType.MSG_TYPE_TEXT);
        assertThat(message.getContentJson()).isEqualTo("{\"text\":\"收到，今天北京晴\"}");
        assertThat(message.getReplyTo()).isZero();
        assertThat(message.getCreatedAtMs()).isEqualTo(1767225600456L);

        Frame rebuilt = Frames.of(Frame.Cmd.CMD_PUSH, 0, PushMessage.newBuilder().setMessage(
                Message.newBuilder()
                        .setMessageId(730000000000000002L)
                        .setConvId(1001)
                        .setSeq(8)
                        .setSenderId(2002)
                        .setMsgType(MsgType.MSG_TYPE_TEXT)
                        .setContentJson("{\"text\":\"收到，今天北京晴\"}")
                        .setCreatedAtMs(1767225600456L)).build());
        assertThat(rebuilt.toByteArray()).isEqualTo(documented);
    }

    @Test
    @DisplayName("PING/PONG 无 payload：字节里只有 cmd，且 req_id=0 时字段缺席")
    void pingHasNoPayload() {
        Frame ping = Frames.of(Frame.Cmd.CMD_PING, 0, null);
        assertThat(ping.toByteArray()).containsExactly((byte) 0x08, (byte) 0x03);
        assertThat(ping.getPayload()).isEqualTo(ByteString.EMPTY);

        // 带 req_id 的 PONG（对客户端 PING 的应答必须回传同一个 req_id）
        Frame pong = Frames.of(Frame.Cmd.CMD_PONG, 17, null);
        assertThat(parse(pong.toByteArray()).getReqId()).isEqualTo(17L);
    }

    @Test
    @DisplayName("用错载荷类型会被守卫拦住（protobuf 自己不会报错，它会把不匹配的字段当未知字段跳过）")
    void wrongBodyTypeFailsLoudly() throws Exception {
        Frame sendFrame = parse(hex(SEND_FRAME_HEX));

        // 先说明危险到底有多大：SendRequest 与 SendAck 的字段 1 都是 conv_id，
        // SendRequest 的字段 2（client_msg_id，LEN）在 AuthRequest 里恰好对应
        // client_version（同为 LEN）。于是一次盲解析会「成功」，只是字段含义全错。
        AuthRequest blind = AuthRequest.parseFrom(sendFrame.getPayload());
        assertThat(blind.getToken()).isEmpty();
        assertThat(blind.getClientVersion()).isEqualTo("c-7f3a9b21");

        // 有守卫之后，这类误用在取数据之前就抛异常。
        assertThatThrownBy(() -> Frames.body(sendFrame, AuthRequest.getDefaultInstance()))
                .isInstanceOf(Frames.FrameBodyException.class)
                .hasMessageContaining("CMD_SEND")
                .hasMessageContaining("AuthRequest");

        // 无载荷的命令也不允许被解析
        assertThatThrownBy(() -> Frames.body(Frames.of(Frame.Cmd.CMD_PING), AuthRequest.getDefaultInstance()))
                .isInstanceOf(Frames.FrameBodyException.class)
                .hasMessageContaining("没有载荷");

        // 正确的类型组合必须通过（防止守卫本身把正常路径也挡了）
        assertThat(Frames.body(sendFrame, SendRequest.getDefaultInstance()).getConvId()).isEqualTo(1001L);
        assertThat(Frames.body(parse(hex(SEND_ACK_FRAME_HEX)), SendAck.getDefaultInstance()).getSeq())
                .isEqualTo(7L);
        assertThat(Frames.body(parse(hex(PUSH_FRAME_HEX)), PushMessage.getDefaultInstance())
                .getMessage().getSeq()).isEqualTo(8L);
    }

    @Test
    @DisplayName("背压丢弃策略：只丢 PUSH，控制帧与回执一律保留")
    void backpressurePolicy() {
        assertThat(Frames.droppableUnderBackpressure(Frame.Cmd.CMD_PUSH)).isTrue();
        for (Frame.Cmd cmd : new Frame.Cmd[]{
                Frame.Cmd.CMD_KICK, Frame.Cmd.CMD_ERROR, Frame.Cmd.CMD_SEND_ACK,
                Frame.Cmd.CMD_AUTH_OK, Frame.Cmd.CMD_PONG, Frame.Cmd.CMD_SYNC_END}) {
            assertThat(Frames.droppableUnderBackpressure(cmd))
                    .as("cmd=%s 不可丢", cmd).isFalse();
        }
    }

    @Test
    @DisplayName("ERROR 帧内容来自错误码枚举：code / retryable 与文档一致")
    void errorFrameCarriesErrorCode() {
        Frame frame = Frames.error(9, com.tm.im.common.error.ErrorCode.TOKEN_EXPIRED, "已于 2026-01-01 过期");
        assertThat(frame.getCmd()).isEqualTo(Frame.Cmd.CMD_ERROR);
        assertThat(frame.getReqId()).isEqualTo(9L);

        com.tm.im.proto.transport.ErrorFrame error =
                Frames.body(frame, com.tm.im.proto.transport.ErrorFrame.getDefaultInstance());
        assertThat(error.getCode()).isEqualTo(40103);
        assertThat(error.getRetryable()).isTrue();
        assertThat(error.getMessage()).contains("token expired").contains("过期");
    }

    private static Frame parse(byte[] bytes) {
        try {
            return Frame.parseFrom(bytes);
        } catch (Exception e) {
            throw new AssertionError("文档里的字节应当能被解析: " + e.getMessage(), e);
        }
    }
}
