package com.tm.im.channel.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

import java.util.List;

/**
 * WebSocket 二进制帧 → {@link ByteBuf}（交给后面的 Protobuf 解码器）。
 *
 * <p>为什么需要这一层：WebSocket 承载下「一个 WS 二进制消息 = 一个完整 Frame」
 * （04-realtime.md §1.2），帧边界由 WS 协议提供，<b>不需要</b>长度前缀。
 * 但 Netty 自带的是 {@code ProtobufDecoder}，它只认 {@code ByteBuf}。
 * 因此这里做一次纯拆包，让「WS 无前缀 / TCP 有前缀」两条路径在
 * Protobuf 解码器之前统一成同一种输入。
 */
public class WsBinaryFrameDecoder extends MessageToMessageDecoder<WebSocketFrame> {

    @Override
    protected void decode(ChannelHandlerContext ctx, WebSocketFrame msg, List<Object> out) {
        if (msg instanceof BinaryWebSocketFrame binary) {
            // 分片在 WebSocketFrameAggregator 里已经合并；此处仍校验 fin，
            // 因为「聚合器被移除」或「顺序被改动」时，半截数据会被当成完整
            // Frame 解析 —— protobuf 对半截数据有时会解析出「看似合法」的对象。
            if (!binary.isFinalFragment()) {
                throw new CorruptedFrameException("收到未结束的二进制分片，拒绝按完整帧解析");
            }
            out.add(binary.content().retain());
            return;
        }
        if (msg instanceof TextWebSocketFrame) {
            // 只用二进制协议。文本帧通常是有人用 websocat / 手写脚本连上来试探，
            // 明确拒绝比静默忽略更容易定位。
            throw new CorruptedFrameException("本协议只接受二进制帧，收到文本帧");
        }
        // Ping/Pong/Close 由 WebSocketServerProtocolHandler 自己处理，不会到这里。
        throw new CorruptedFrameException("不支持的 WebSocket 帧类型: " + msg.getClass().getSimpleName());
    }
}
