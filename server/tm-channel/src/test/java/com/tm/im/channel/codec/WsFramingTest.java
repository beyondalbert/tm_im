package com.tm.im.channel.codec;

import com.tm.im.proto.transport.Frame;
import com.tm.im.proto.transport.SendRequest;
import com.tm.im.proto.transport.MsgType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WebSocket 承载：<b>一个 WS 二进制消息 = 一个完整 Frame，没有长度前缀</b>
 * （04-realtime.md §1.2）。这里的每一个断言都对应一种「不这么处理就会静默出错」的情况。
 */
class WsFramingTest {

    private static EmbeddedChannel channel(int maxFrameBytes) {
        // 顺序与 ChannelPipelineInitializer 中一致（见那里的注释）：
        // 聚合器 → WS 帧编码器 → WS 帧解码器 → protobuf 编解码器
        return new EmbeddedChannel(
                new WebSocketFrameAggregator(maxFrameBytes),
                new WsBinaryFrameEncoder(),
                new WsBinaryFrameDecoder(),
                new ProtobufEncoder(),
                new ProtobufDecoder(Frame.getDefaultInstance()));
    }

    private static Frame sampleSend() {
        return Frames.of(Frame.Cmd.CMD_SEND, 2, SendRequest.newBuilder()
                .setConvId(1001)
                .setClientMsgId("c-7f3a9b21")
                .setMsgType(MsgType.MSG_TYPE_TEXT)
                .setContentJson("{\"text\":\"你好，Agent\"}")
                .build());
    }

    @Test
    @DisplayName("入站：一个 WS 二进制消息 → 一条 Frame（无需长度前缀）")
    void binaryFrameDecodesWithoutLengthPrefix() {
        EmbeddedChannel channel = channel(1024 * 1024);
        Frame expected = sampleSend();

        channel.writeInbound(new BinaryWebSocketFrame(
                Unpooled.wrappedBuffer(expected.toByteArray())));

        Frame decoded = channel.readInbound();
        assertThat(decoded).isEqualTo(expected);
        assertThat((Object) channel.readInbound()).isNull();
    }

    @Test
    @DisplayName("出站：Frame → 一个 WS 二进制帧，内容就是 protobuf 字节")
    void frameEncodesToBinaryWebSocketFrame() {
        EmbeddedChannel channel = channel(1024 * 1024);
        Frame frame = sampleSend();
        channel.writeOutbound(frame);

        BinaryWebSocketFrame out = channel.readOutbound();
        byte[] bytes = new byte[out.content().readableBytes()];
        out.content().readBytes(bytes);
        assertThat(bytes).isEqualTo(frame.toByteArray());
        out.release();
    }

    @Test
    @DisplayName("分片消息：拆成两个 WS 帧也必须还原成一条完整 Frame")
    void fragmentedMessageIsAggregated() {
        EmbeddedChannel channel = channel(1024 * 1024);
        Frame expected = sampleSend();
        byte[] bytes = expected.toByteArray();
        int split = 10;

        // 第一片：fin=false 的二进制帧；第二片：fin=true 的续帧。
        // 不聚合的话，半截 protobuf 会被当成完整帧解析 —— 而 protobuf 对
        // 「截断的输入」有时会给出一个看似合法、字段却缺失的对象，不报错。
        channel.writeInbound(
                new BinaryWebSocketFrame(false, 0, Unpooled.wrappedBuffer(bytes, 0, split)),
                new ContinuationWebSocketFrame(true, 0,
                        Unpooled.wrappedBuffer(bytes, split, bytes.length - split)));

        Frame decoded = channel.readInbound();
        assertThat(decoded).isEqualTo(expected);
        assertThat((Object) channel.readInbound()).isNull();
    }

    @Test
    @DisplayName("文本帧被明确拒绝（而不是被当成二进制解析）")
    void textFrameIsRejected() {
        EmbeddedChannel channel = channel(1024 * 1024);

        // 解码器直接抛出异常（不是存进 checkException）——这是本版本 Netty 的实际行为
        assertThatThrownBy(() -> channel.writeInbound(new TextWebSocketFrame("{\"cmd\":1}")))
                .isInstanceOf(CorruptedFrameException.class)
                .hasMessageContaining("二进制");
        assertThat((Object) channel.readInbound()).isNull();
    }

    @Test
    @DisplayName("超过上限的分片消息被拒绝（防止用分片绕过单帧上限）")
    void oversizedFragmentedMessageIsRejected() {
        EmbeddedChannel channel = channel(32);
        ByteBuf big = Unpooled.wrappedBuffer(new byte[64]);

        assertThatThrownBy(() -> {
            channel.writeInbound(new BinaryWebSocketFrame(false, 0, big.retainedSlice(0, 16)));
            channel.writeInbound(new ContinuationWebSocketFrame(true, 0, big.retainedSlice(16, 48)));
        }).isInstanceOf(TooLongFrameException.class);

        big.release();
    }

    @Test
    @DisplayName("与 TCP 路径语义一致：同一条 Frame 在两种承载下产出相同的 protobuf 字节")
    void bothTransportsCarryIdenticalBytes() {
        Frame frame = sampleSend();

        EmbeddedChannel ws = channel(1024 * 1024);
        ws.writeOutbound(frame);
        BinaryWebSocketFrame wsOut = ws.readOutbound();
        byte[] viaWs = new byte[wsOut.content().readableBytes()];
        wsOut.content().readBytes(viaWs);

        // 文档 §4.2 的 SEND 帧字节
        assertThat(viaWs).isEqualTo(HexFormat.of().parseHex("""
                080a10021a2c08e907120a632d3766336139623231180122197b2274657874\
                223a22e4bda0e5a5bdefbc8c4167656e74227d"""));
        wsOut.release();
    }
}
