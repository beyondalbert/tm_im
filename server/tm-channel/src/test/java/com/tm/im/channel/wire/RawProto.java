package com.tm.im.channel.wire;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 手写的极简 protobuf 编解码，只服务于测试里的「独立客户端」。
 *
 * <p><b>为什么不直接用 protobuf-java</b>：被测服务端的编解码也是 protobuf-java。
 * 两端共用同一个库时，「服务端理解错了字段」与「客户端按同样的错误构造」会互相抵消，
 * 测试依然是绿的。这里完全按 {@code proto/transport.proto} 的字段号手写字节，
 * 与 {@code docs/integration/04-realtime.md} §4 的实测向量同一口径 ——
 * 它校验的是<b>线上字节</b>，而不是「两边的库版本恰好一致」。
 *
 * <p>只实现测试用到的那几种线类型（varint / 长度分隔），其余一律抛异常：
 * 与其宽容地跳过不认识的字节，不如让协议一旦变形就立刻失败。
 */
public final class RawProto {

    private RawProto() {
    }

    // ---------- 编码 ----------

    public static void writeVarint(ByteArrayOutputStream out, long value) {
        // 无符号 LEB128：负数按 64 位补码处理（负数的 int64 会占满 10 字节，这是正确的）
        while (true) {
            int b = (int) (value & 0x7F);
            value >>>= 7;
            if (value == 0) {
                out.write(b);
                return;
            }
            out.write(b | 0x80);
        }
    }

    private static void writeTag(ByteArrayOutputStream out, int field, int wireType) {
        writeVarint(out, ((long) field << 3) | wireType);
    }

    /** varint 字段。proto3 的隐式默认值不上线，因此 0 直接不写。 */
    public static byte[] varintField(int field, long value) {
        if (value == 0) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTag(out, field, 0);
        writeVarint(out, value);
        return out.toByteArray();
    }

    /** 长度分隔字段（string 与嵌套 message 共用）。空字符串同样不上线。 */
    public static byte[] lenField(int field, byte[] payload) {
        if (payload == null || payload.length == 0) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTag(out, field, 2);
        writeVarint(out, payload.length);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    public static byte[] stringField(int field, String value) {
        return value == null ? new byte[0] : lenField(field, value.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            if (p != null) {
                out.writeBytes(p);
            }
        }
        return out.toByteArray();
    }

    /**
     * Frame 的编码（proto: Frame）。
     * 字段按序号升序写出，与 protobuf-java 的规范输出一致 —— 因此这里产出的字节
     * 可以与文档里的实测 hex 直接比对。
     */
    public static byte[] frame(int cmd, long reqId, byte[] payload) {
        return concat(varintField(1, cmd), varintField(2, reqId), lenField(3, payload));
    }

    /** AuthRequest（proto: AuthRequest）。 */
    public static byte[] authRequest(String token, String clientVersion, String deviceId) {
        return concat(stringField(1, token), stringField(2, clientVersion), stringField(3, deviceId));
    }

    // ---------- 解码 ----------

    /**
     * 解析一个消息为「字段号 → 值」。varint 存 {@link Long}，长度分隔存 {@code byte[]}。
     *
     * <p>遇到不认识的线类型、或字节流在中途截断，一律抛异常：这两种情况都意味着
     * 「线上的字节不是我们以为的协议」，静默跳过只会把问题推迟到业务层。
     */
    public static Map<Integer, Object> parse(byte[] message) {
        Map<Integer, Object> fields = new LinkedHashMap<>();
        int i = 0;
        while (i < message.length) {
            long tag;
            int[] cursor = {i};
            tag = readVarint(message, cursor);
            i = cursor[0];

            int field = (int) (tag >>> 3);
            int wireType = (int) (tag & 0x7);
            if (field <= 0) {
                throw new IllegalArgumentException("字段号非法: " + field);
            }

            switch (wireType) {
                case 0 -> {
                    cursor[0] = i;
                    long v = readVarint(message, cursor);
                    i = cursor[0];
                    fields.put(field, v);
                }
                case 2 -> {
                    cursor[0] = i;
                    long len = readVarint(message, cursor);
                    i = cursor[0];
                    if (len < 0 || i + len > message.length) {
                        throw new IllegalArgumentException(
                                "字段 " + field + " 声明长度 " + len + " 超出剩余 " + (message.length - i) + " 字节");
                    }
                    byte[] v = new byte[(int) len];
                    System.arraycopy(message, i, v, 0, (int) len);
                    i += (int) len;
                    fields.put(field, v);
                }
                default -> throw new IllegalArgumentException(
                        "不支持的线类型 " + wireType + "（字段 " + field + "）——线上协议已变形");
            }
        }
        return fields;
    }

    private static long readVarint(byte[] buf, int[] cursor) {
        long result = 0;
        int shift = 0;
        while (true) {
            if (cursor[0] >= buf.length) {
                throw new IllegalArgumentException("varint 在字节流末尾被截断");
            }
            int b = buf[cursor[0]++] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift > 63) {
                throw new IllegalArgumentException("varint 超过 10 字节");
            }
        }
    }

    /** 取 varint 字段；缺失按 proto3 语义返回 0。类型不符直接失败。 */
    public static long number(Map<Integer, Object> fields, int field) {
        Object v = fields.get(field);
        if (v == null) {
            return 0L;
        }
        if (!(v instanceof Long l)) {
            throw new IllegalArgumentException("字段 " + field + " 不是 varint，实际是长度分隔");
        }
        return l;
    }

    /** 取长度分隔字段；缺失返回 null。 */
    public static byte[] bytes(Map<Integer, Object> fields, int field) {
        Object v = fields.get(field);
        if (v == null) {
            return null;
        }
        if (!(v instanceof byte[] b)) {
            throw new IllegalArgumentException("字段 " + field + " 不是长度分隔，实际是 varint");
        }
        return b;
    }

    public static String text(Map<Integer, Object> fields, int field) {
        byte[] b = bytes(fields, field);
        return b == null ? "" : new String(b, StandardCharsets.UTF_8);
    }

    public static boolean flag(Map<Integer, Object> fields, int field) {
        return number(fields, field) != 0;
    }
}
