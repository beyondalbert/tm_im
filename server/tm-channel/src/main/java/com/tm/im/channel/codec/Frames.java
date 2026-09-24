package com.tm.im.channel.codec;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.proto.transport.AuthRequest;
import com.tm.im.proto.transport.AuthResponse;
import com.tm.im.proto.transport.ErrorFrame;
import com.tm.im.proto.transport.Frame;
import com.tm.im.proto.transport.KickNotice;
import com.tm.im.proto.transport.KickReason;
import com.tm.im.proto.transport.PushMessage;
import com.tm.im.proto.transport.ReadRequest;
import com.tm.im.proto.transport.SendAck;
import com.tm.im.proto.transport.SendRequest;
import com.tm.im.proto.transport.SyncEnd;
import com.tm.im.proto.transport.SyncRequest;
import com.tm.im.proto.transport.SyncResponse;

/**
 * {@link Frame} 的构造与解包助手。
 *
 * <p>集中在这里的原因是 {@code payload} 是 {@code bytes}：它到底该按哪个消息解析，
 * 只由 {@code cmd} 决定。把这个对应关系散落在各个 handler 里，
 * 早晚会出现「A 处按 SendAck 解析、B 处按 PushMessage 解析」而没人发现的情况——
 * protobuf 在字段号兼容时会<b>静默解析成功但字段含义错位</b>。
 * 因此这里提供唯一的构建入口，并要求解包时显式给出目标类型。
 */
public final class Frames {

    /** 命令字的方向（04-realtime.md §2.1 的「方向」列）。 */
    public enum Direction {
        CLIENT_TO_SERVER,
        SERVER_TO_CLIENT,
        BOTH,
        /** 保留值：不属于任何方向（{@code CMD_UNKNOWN} 与 protobuf 的 {@code UNRECOGNIZED}）。 */
        NONE;

        /**
         * 客户端能否发送该方向的命令。
         *
         * <p>保留值与「服务端专用」都不行：前者是协议保留的编号，后者发过来意味着
         * 客户端把自己的响应当请求回显了（或者是伪造的帧）。
         */
        public boolean fromClient() {
            return this == CLIENT_TO_SERVER || this == BOTH;
        }
    }

    private Frames() {
    }

    /** 构造带消息体的帧。{@code body} 为 null 时 payload 留空（PING/PONG 就是这么用的）。 */
    public static Frame of(Frame.Cmd cmd, long reqId, MessageLite body) {
        Frame.Builder builder = Frame.newBuilder().setCmd(cmd).setReqId(reqId);
        if (body != null) {
            builder.setPayload(body.toByteString());
        }
        return builder.build();
    }

    public static Frame of(Frame.Cmd cmd) {
        return of(cmd, 0, null);
    }

    /**
     * 解包为指定类型。
     *
     * <p><b>为什么必须校验「命令字 ↔ 载荷类型」的对应关系</b>：protobuf 对此
     * <b>完全不报错</b>——当某个字段的线类型与目标类型期望的不一致时
     * （例如把 varint 类型的 {@code conv_id} 按 LEN 类型的 {@code token} 去解），
     * protobuf 会把它当成<b>未知字段跳过</b>，然后返回一个「解析成功」的对象：
     * 有的字段为空、有的字段装的是别的字段的值。
     * 这种错误没有任何异常、没有任何日志，只会在业务层表现为「数据莫名其妙不对」。
     *
     * <p>下面的 {@link #expectedBodyType} 把这条对应关系显式写出来并强制校验，
     * 于是「用错类型」从静默错位变成了抛异常。
     *
     * <p>解析失败抛 {@link FrameBodyException}（上层映射为 40002 而不是 50000）：
     * 能走到这一步说明帧已经过编解码、连接已鉴权，那么载荷格式错<b>就是</b>客户端的问题。
     */
    public static <T extends MessageLite> T body(Frame frame, T prototype) {
        Class<? extends MessageLite> expected = expectedBodyType(frame.getCmd());
        if (expected == null) {
            throw new FrameBodyException(
                    "命令 " + frame.getCmd() + " 没有载荷，不应尝试解析");
        }
        if (!expected.equals(prototype.getClass())) {
            throw new FrameBodyException(
                    "命令 " + frame.getCmd() + " 的载荷必须是 " + expected.getSimpleName()
                            + "，实际按 " + prototype.getClass().getSimpleName() + " 解析");
        }
        try {
            @SuppressWarnings("unchecked")
            T parsed = (T) prototype.getParserForType().parseFrom(frame.getPayload());
            return parsed;
        } catch (InvalidProtocolBufferException e) {
            throw new FrameBodyException(
                    "payload 无法按 " + prototype.getClass().getSimpleName() + " 解析（cmd="
                            + frame.getCmd() + ", " + frame.getPayload().size() + " 字节）", e);
        }
    }

    /**
     * 命令字 → 载荷类型的唯一对应表（04-realtime.md §2.1 的代码投影）。
     *
     * <p>返回 {@code null} 表示该命令无载荷（PING/PONG 与保留值）。
     * 新增命令时这里会因 switch 不完备而编译失败（没有 default 分支），
     * 从而强制实现者想清楚「这个命令的载荷是什么」。
     */
    private static Class<? extends MessageLite> expectedBodyType(Frame.Cmd cmd) {
        return switch (cmd) {
            case CMD_AUTH -> AuthRequest.class;
            case CMD_AUTH_OK -> AuthResponse.class;
            case CMD_SEND -> SendRequest.class;
            case CMD_SEND_ACK -> SendAck.class;
            case CMD_PUSH -> PushMessage.class;
            case CMD_READ -> ReadRequest.class;
            case CMD_SYNC -> SyncRequest.class;
            case CMD_SYNC_RESP -> SyncResponse.class;
            case CMD_SYNC_END -> SyncEnd.class;
            case CMD_KICK -> KickNotice.class;
            case CMD_ERROR -> ErrorFrame.class;
            case CMD_PING, CMD_PONG, CMD_UNKNOWN, UNRECOGNIZED -> null;
        };
    }

    /**
     * 命令字 → 方向的唯一对应表（04-realtime.md §2.1 的「方向」列）。
     *
     * <p>这张表存在，是因为踩过一个坑：{@code CMD_SYNC(14)} 曾被同时定义成
     * 请求（{@code SyncRequest}）与响应（{@code SyncResponse}）。同一条连接上，
     * 收到 {@code cmd=14} 的一端<b>无法判断</b>这是「对方要我补消息」还是
     * 「对方给我补了消息」——而 protobuf 又不会报错，字段号近似时两种都能解出来，
     * 于是成了「解析成功但字段含义全错」。修正：响应独立编号
     * {@code CMD_SYNC_RESP(16)}，而不是让实现者去猜载荷（04-realtime.md §2.3）。
     *
     * <p>拿到方向之后，「客户端发来服务端专用命令」不再掉进各 handler 的
     * {@code default} 分支里被含混地当成「未知命令」，而可以给出明确的 40000 说明。
     *
     * <p>与 {@link #expectedBodyType} 同样<b>不写 default</b>：新增命令字时此处编译失败，
     * 逼实现者声明它的方向。
     */
    public static Direction direction(Frame.Cmd cmd) {
        return switch (cmd) {
            case CMD_AUTH, CMD_SEND, CMD_READ, CMD_SYNC -> Direction.CLIENT_TO_SERVER;
            case CMD_AUTH_OK, CMD_SEND_ACK, CMD_PUSH, CMD_SYNC_RESP, CMD_SYNC_END,
                 CMD_KICK, CMD_ERROR -> Direction.SERVER_TO_CLIENT;
            case CMD_PING, CMD_PONG -> Direction.BOTH;
            case CMD_UNKNOWN, UNRECOGNIZED -> Direction.NONE;
        };
    }

    /** 成功/失败回执都用它：{@code req_id} 原样回传是客户端配对响应的唯一依据。 */
    public static Frame error(long reqId, ErrorCode code, String detail) {
        return of(Frame.Cmd.CMD_ERROR, reqId, ErrorFrame.newBuilder()
                .setCode(code.code())
                .setMessage(detail == null || detail.isBlank()
                        ? code.message()
                        : code.message() + ": " + detail)
                .setRetryable(code.retryable())
                .build());
    }

    public static Frame kick(KickReason reason, String detail, long retryAfterMs) {
        return of(Frame.Cmd.CMD_KICK, 0, KickNotice.newBuilder()
                .setReason(reason)
                .setDetail(detail == null ? "" : detail)
                .setRetryAfterMs((int) Math.min(retryAfterMs, Integer.MAX_VALUE))
                .build());
    }

    /**
     * 背压（DESIGN §7.6）：连接不可写时哪些帧可以丢。
     *
     * <p>只丢 {@code PUSH}：它承载的是「新消息通知」，而客户端有
     * {@code SYNC(since_seq)} 这条按 seq 补齐的路径 —— 丢掉再同步回来即可。
     * 其余都不能丢：{@code KICK} 是终态控制帧（丢了客户端会一直重连到错误的节点）、
     * {@code ERROR} 是客户端重试决策的依据、{@code SEND_ACK} 承载最终 seq
     * （丢了客户端不知道消息是否落库，只能重发，而重发依赖幂等键兜底）。
     *
     * <p>{@code SYNC_RESP} 也不能丢：它虽然也能重新请求，但客户端此刻正处
     * 「先补齐再接收实时」的状态（04-realtime.md §6.2），丢掉它的表现是续传静默卡住，
     * 直到客户端自己的超时才发现。宁可断开让客户端重连，也不要让它悬着。
     */
    public static boolean droppableUnderBackpressure(Frame.Cmd cmd) {
        return cmd == Frame.Cmd.CMD_PUSH;
    }

    /** 载荷解析失败。单独的异常类型，便于上层映射成 40002 而不是 50000。 */
    public static final class FrameBodyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public FrameBodyException(String message) {
            super(message);
        }

        public FrameBodyException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
