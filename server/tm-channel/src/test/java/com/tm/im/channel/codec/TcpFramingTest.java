package com.tm.im.channel.codec;

import com.tm.im.proto.transport.Frame;
import com.tm.im.proto.transport.SendAck;
import com.tm.im.proto.transport.SendRequest;
import com.tm.im.proto.transport.MsgType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 原生 TCP 承载：4 字节大端长度前缀（04-realtime.md §1.2）。
 *
 * <p>TCP 是字节流，没有消息边界。这里验证三件事：
 * 前缀的长度<b>不含自身 4 字节</b>、半包要等齐、粘包要切开。
 * 后两者是 TCP 上最容易写错的地方 —— 它们在「客户端快速连发」与
 * 「网络刚好把一个帧切成两段」时才出现，本地单发一条消息测不出来。
 */
class TcpFramingTest {

    /** 文档 §1.2 的示例：长度 0x32 = 50，后面是那 50 字节的 SEND 帧。 */
    private static final byte[] DOC_SEND_FRAME = HexFormat.of().parseHex("""
            080a10021a2c08e907120a632d3766336139623231180122197b2274657874\
            223a22e4bda0e5a5bdefbc8c4167656e74227d""");

    private static EmbeddedChannel channel(int maxFrameBytes) {
        return new EmbeddedChannel(
                new LengthFieldBasedFrameDecoder(maxFrameBytes, 0, 4, 0, 4),
                new LengthFieldPrepender(4),
                new ProtobufEncoder(),
                new ProtobufDecoder(Frame.getDefaultInstance()));
    }

    private static SendRequest sampleSend() {
        return SendRequest.newBuilder()
                .setConvId(1001)
                .setClientMsgId("c-7f3a9b21")
                .setMsgType(MsgType.MSG_TYPE_TEXT)
                .setContentJson("{\"text\":\"你好，Agent\"}")
                .build();
    }

    @Test
    @DisplayName("出站：长度前缀是「帧字节数」而不是「帧字节数 + 4」，与文档 §1.2 的实测一致")
    void lengthPrefixExcludesItself() {
        EmbeddedChannel channel = channel(1024 * 1024);
        channel.writeOutbound(Frames.of(Frame.Cmd.CMD_SEND, 2, sampleSend()));

        // LengthFieldPrepender 产出的是「头 + 体」两个 ByteBuf，
        // 在真实连接上它们连续写出去；测试里要把它们拼起来才等于线上字节。
        byte[] onWire = drainOutbound(channel);
        assertThat(onWire).hasSize(4 + DOC_SEND_FRAME.length);
        assertThat(java.util.Arrays.copyOfRange(onWire, 0, 4))
                .isEqualTo(HexFormat.of().parseHex("00000032"));   // 大端 50
        assertThat(onWire[0]).isZero();                          // 分流判据依赖它
        assertThat(java.util.Arrays.copyOfRange(onWire, 4, onWire.length)).isEqualTo(DOC_SEND_FRAME);
    }

    @Test
    @DisplayName("入站半包：逐字节喂入，最后一个字节到达前不产出任何帧")
    void halfPacketWaitsForCompletion() {
        EmbeddedChannel channel = channel(1024 * 1024);
        byte[] onWire = docSendFrameWithPrefix();

        for (int i = 0; i < onWire.length; i++) {
            channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{onWire[i]}));
            if (i < onWire.length - 1) {
                // 关键断言：数据不齐时绝不能提前产出「半个帧」。
                // 提前产出的话，protobuf 有时能解析出一个字段错乱的对象并且不报错。
                assertThat((Object) channel.readInbound()).as("第 %d 个字节后不应产出帧", i + 1).isNull();
            }
        }
        Frame decoded = channel.readInbound();
        assertThat(decoded).isNotNull();
        assertThat(decoded.getCmd()).isEqualTo(Frame.Cmd.CMD_SEND);
        assertThat(Frames.body(decoded, SendRequest.getDefaultInstance()).getConvId()).isEqualTo(1001L);
    }

    @Test
    @DisplayName("入站粘包：两条帧挤在一次读取里，必须切出两条且顺序不变")
    void stickyPacketSplitsIntoTwoFrames() {
        EmbeddedChannel channel = channel(1024 * 1024);
        byte[] first = docSendFrameWithPrefix();
        byte[] second = prefixed(Frames.of(Frame.Cmd.CMD_SEND_ACK, 3, SendAck.newBuilder()
                .setConvId(1001).setSeq(9).setMessageId(42L).setClientMsgId("c-7f3a9b21").build()));

        ByteBuf merged = Unpooled.buffer(first.length + second.length);
        merged.writeBytes(first).writeBytes(second);
        channel.writeInbound(merged);

        Frame a = channel.readInbound();
        Frame b = channel.readInbound();
        assertThat(a.getCmd()).isEqualTo(Frame.Cmd.CMD_SEND);
        assertThat(b.getCmd()).isEqualTo(Frame.Cmd.CMD_SEND_ACK);
        assertThat(Frames.body(b, SendAck.getDefaultInstance()).getSeq()).isEqualTo(9L);
        assertThat((Object) channel.readInbound()).isNull();
    }

    @Test
    @DisplayName("超过单帧上限的帧被拒绝（否则一次恶意长度就能让服务端分配巨量内存）")
    void oversizedFrameIsRejected() {
        EmbeddedChannel channel = channel(64);
        ByteBuf tooLong = Unpooled.buffer();
        tooLong.writeInt(100_000);          // 声称 100000 字节
        tooLong.writeZero(16);              // 只给了 16 字节

        // 注意本版本 Netty 的语义：解码器把异常直接抛出 writeInbound，
        // 不是存进 checkException()。
        assertThatThrownBy(() -> channel.writeInbound(tooLong))
                .isInstanceOf(TooLongFrameException.class)
                .hasMessageContaining("64");
    }

    @Test
    @DisplayName("往返：写入 Frame → 出线字节 → 再读回同一条 Frame")
    void roundTrip() {
        EmbeddedChannel out = channel(1024 * 1024);
        Frame original = Frames.of(Frame.Cmd.CMD_SEND, 7, sampleSend());
        out.writeOutbound(original);

        EmbeddedChannel in = channel(1024 * 1024);
        in.writeInbound(Unpooled.wrappedBuffer(drainOutbound(out)));

        Frame decoded = in.readInbound();
        assertThat(decoded).isEqualTo(original);   // protobuf 消息的 equals 是逐字段比较
    }

    /** 把出站队列里所有 ByteBuf 拼成线上字节（LengthFieldPrepender 会写两个对象）。 */
    private static byte[] drainOutbound(EmbeddedChannel channel) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ByteBuf buf;
        while ((buf = channel.readOutbound()) != null) {
            byte[] chunk = new byte[buf.readableBytes()];
            buf.readBytes(chunk);
            buf.release();
            out.writeBytes(chunk);
        }
        return out.toByteArray();
    }

    private static byte[] docSendFrameWithPrefix() {
        return concat(HexFormat.of().parseHex("00000032"), DOC_SEND_FRAME);
    }

    private static byte[] prefixed(Frame frame) {
        return concat(HexFormat.of().parseHex(String.format("%08x", frame.getSerializedSize())),
                frame.toByteArray());
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
