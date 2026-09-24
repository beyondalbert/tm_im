package com.tm.im.channel.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

import java.util.List;

/**
 * {@link ByteBuf} → WebSocket 二进制帧。
 *
 * <p>与 {@link WsBinaryFrameDecoder} 对称：Protobuf 编码器产出 ByteBuf 之后，
 * WS 路径需要把它包成 WS 帧，而 TCP 路径需要的是长度前缀（由
 * {@code LengthFieldPrepender} 处理）。两条路径的分叉就集中在这两个类与
 * 那两个 Netty 内置处理器上。
 */
public class WsBinaryFrameEncoder extends MessageToMessageEncoder<ByteBuf> {

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
        // retain()：MessageToMessageEncoder 结束时会释放入参，
        // 而我们要把同一块内存交给下一个处理器（WebSocketFrameEncoder 再编码成字节）。
        out.add(new BinaryWebSocketFrame(msg.retain()));
    }
}
