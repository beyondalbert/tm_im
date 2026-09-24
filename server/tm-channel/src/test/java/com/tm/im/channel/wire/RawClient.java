package com.tm.im.channel.wire;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 走真实 socket 的独立客户端，支持两种承载（04-realtime.md §1.1）：
 * <pre>
 * 原生 TCP : 4 字节大端长度前缀 + Frame
 * WebSocket: HTTP 升级后，一个二进制消息 = 一个 Frame
 * </pre>
 *
 * <p>它是「手写协议 + 手写 WS 帧」，不复用服务端的任何一行代码。这样做的价值在
 * {@link RawProto} 的类注释里说过：只有独立实现才能真正验证线上格式。
 * 附带好处是它对 RFC 6455 的要求（客户端必须掩码、升级响应必须校验
 * {@code Sec-WebSocket-Accept}）也一并检查了 —— 服务端握手写错时这里会直接失败。
 */
public final class RawClient implements Closeable {

    /** RFC 6455 §1.3 的固定 GUID。 */
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static final int OP_CONTINUATION = 0x0;
    private static final int OP_TEXT = 0x1;
    private static final int OP_BINARY = 0x2;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final boolean webSocket;    private final SecureRandom random = new SecureRandom();

    private final ByteArrayOutputStream fragments = new ByteArrayOutputStream();
    private boolean fragmenting;

    private RawClient(Socket socket, boolean webSocket) throws IOException {
        this.socket = socket;
        this.webSocket = webSocket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    public static RawClient tcp(String host, int port, int timeoutMs) throws IOException {
        return connect(host, port, timeoutMs, false);
    }

    public static RawClient webSocket(String host, int port, String path, int timeoutMs) throws IOException {
        // 注意：webSocket 标志必须在握手之前就置位。这个字段决定「怎么收发帧」，
        // 忘了置位的话，WS 客户端会用长度前缀去读 WS 帧 ——
        // 症状是「长度前缀非法: <一堆看似随机的数字>」，与协议本身无关。
        RawClient client = connect(host, port, timeoutMs, true);
        try {
            client.handshake(host, port, path);
        } catch (IOException | RuntimeException e) {
            client.close();
            throw e;
        }
        return client;
    }

    private static RawClient connect(String host, int port, int timeoutMs, boolean webSocket)
            throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(timeoutMs);
        return new RawClient(socket, webSocket);
    }

    // ---------- 发送 ----------

    /** 发送一条 Frame：内部按承载方式加上长度前缀或 WS 头。 */
    public void send(int cmd, long reqId, byte[] payload) throws IOException {
        sendRaw(RawProto.frame(cmd, reqId, payload));
    }

    public void sendRaw(byte[] frame) throws IOException {
        if (webSocket) {
            writeWebSocketFrame(OP_BINARY, frame);
        } else {
            out.write(ByteBuffer.allocate(4).putInt(frame.length).array());
            out.write(frame);
        }
        out.flush();
    }

    /**
     * 不经过任何封装，直接写原始字节。
     *
     * <p>用于构造半包场景（如「只发出了长度前缀的第一个字节」）。
     * 这种输入在真实网络里很常见（TCP 会任意切分），但用「发一个完整帧」的方式
     * 无论如何都造不出来。
     */
    public void sendRawBytes(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    // ---------- 接收 ----------

    /**
     * 收一条 Frame 的已解析字段。
     *
     * @return 为 {@code null} 表示对端已正常关闭（TCP FIN 或 WS Close 帧）
     * @throws SocketTimeoutException 超时未收到任何数据（测试里通常意味着服务端漏回了）
     */
    public Map<Integer, Object> receive() throws IOException {
        byte[] frame = receiveFrameBytes();
        return frame == null ? null : RawProto.parse(frame);
    }

    /**
     * 收一条 Frame 的<b>原始字节</b>（不含长度前缀 / WS 头）。
     *
     * <p>需要它是因为「字段值对」和「线上字节对」是两件事：proto3 的隐式默认值
     * 不应上线、字段必须按序号升序 —— 这些只有逐字节断言才能证明，
     * 而那正是接入文档里给各语言 SDK 的实测样本口径。
     */
    public byte[] receiveFrameBytes() throws IOException {
        return webSocket ? readWebSocketMessage() : readLengthPrefixed();
    }

    /** 收原始字节并要求必须存在。 */
    public byte[] expectFrameBytes(String what) throws IOException {
        byte[] frame = receiveFrameBytes();
        if (frame == null) {
            throw new EOFException("对端在收到 " + what + " 之前就关闭了连接");
        }
        return frame;
    }

    /** 收一条 Frame 并要求它必须存在：不存在就带着上下文失败。 */
    public Map<Integer, Object> expectFrame(String what) throws IOException {
        Map<Integer, Object> frame = receive();
        if (frame == null) {
            throw new EOFException("对端在收到 " + what + " 之前就关闭了连接");
        }
        return frame;
    }

    /**
     * 断言对端确实关闭了连接（而不是悄悄不回）。
     *
     * <p>{@code KICK}/{@code ERROR} 之后服务端必须主动断开 —— 只发错误帧不断开的实现
     * 会让客户端不知道该不该重连，这类缺陷只有真连一遍才看得出来。
     */
    public boolean isClosedByPeer() throws IOException {
        try {
            return receive() == null;
        } catch (SocketTimeoutException e) {
            return false;
        }
    }

    private byte[] readLengthPrefixed() throws IOException {
        byte[] header = readFully(4);
        if (header == null) {
            return null;
        }
        int length = ByteBuffer.wrap(header).getInt();
        if (length <= 0 || length > (1 << 24)) {
            throw new IOException("长度前缀非法: " + length);
        }
        byte[] body = readFully(length);
        if (body == null) {
            throw new IOException("声明 " + length + " 字节，但连接在正文中间就断了");
        }
        return body;
    }

    private byte[] readWebSocketMessage() throws IOException {
        while (true) {
            byte[] header = readFully(2);
            if (header == null) {
                return null;
            }
            boolean fin = (header[0] & 0x80) != 0;
            int opcode = header[0] & 0x0F;
            boolean masked = (header[1] & 0x80) != 0;
            long length = header[1] & 0x7F;

            if (length == 126) {
                byte[] ext = readFully(2);
                length = ByteBuffer.wrap(ext).getShort() & 0xFFFFL;
            } else if (length == 127) {
                byte[] ext = readFully(8);
                length = ByteBuffer.wrap(ext).getLong();
            }
            if (length > (1 << 24)) {
                throw new IOException("WS 帧声明长度过大: " + length);
            }

            byte[] mask = null;
            if (masked) {
                mask = readFully(4);
            }
            byte[] payload = readFully((int) length);
            if (payload == null) {
                return null;
            }
            if (masked) {
                unmask(payload, mask);
            }

            switch (opcode) {
                case OP_CLOSE -> {
                    // RFC 6455 §5.5.1：收到 Close 要回一个 Close（可以带原状态码）。
                    writeWebSocketFrame(OP_CLOSE, payload.length >= 2
                            ? new byte[]{payload[0], payload[1]} : new byte[0]);
                    return null;
                }
                case OP_PING -> {
                    writeWebSocketFrame(OP_PONG, payload);
                    continue;
                }
                case OP_PONG -> {
                    continue;
                }
                case OP_TEXT -> throw new IOException("服务端发来了文本帧：本协议规定只发二进制帧");
                case OP_BINARY, OP_CONTINUATION -> {
                    // 分片消息：不聚合的话，半截 protobuf 会被当成完整帧解析。
                    if (opcode == OP_BINARY && fragmenting) {
                        throw new IOException("上一条分片消息还没结束，又来了新的二进制帧");
                    }
                    if (opcode == OP_CONTINUATION && !fragmenting) {
                        throw new IOException("收到了没有起始帧的续帧");
                    }
                    fragments.write(payload);
                    if (fin) {
                        byte[] message = fragments.toByteArray();
                        fragments.reset();
                        fragmenting = false;
                        return message;
                    }
                    fragmenting = true;
                }
                default -> throw new IOException("未知 WS 操作码: " + opcode);
            }
        }
    }

    // ---------- WS 握手与帧编码 ----------

    private void handshake(String host, int port, String path) throws IOException {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);

        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        String response = readHttpResponseHead();
        if (!response.startsWith("HTTP/1.1 101")) {
            throw new IOException("WebSocket 升级失败，服务端返回：" + response.lines().findFirst().orElse("<空>"));
        }

        // 校验 Accept：这是唯一能证明「对端真的实现了 WS 握手」的东西。
        // 缺了它，一个把 Sec-WebSocket-Key 原样回显的假实现也能让测试通过。
        String expected = websocketAccept(key);
        Map<String, String> headers = parseHeaders(response);
        String actual = headers.get("sec-websocket-accept");
        if (!expected.equals(actual)) {
            throw new IOException("Sec-WebSocket-Accept 不匹配：期望 " + expected + "，实际 " + actual);
        }
    }

    private static String websocketAccept(String key) throws IOException {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("JDK 缺少 SHA-1", e);
        }
    }

    private String readHttpResponseHead() throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (head.size() < 16 * 1024) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("HTTP 响应头未读完连接就断了");
            }
            head.write(b);
            // 以 \r\n\r\n 结束（逐字节状态机，避免依赖 readLine 的编码行为）
            state = switch (state) {
                case 0 -> b == '\r' ? 1 : 0;
                case 1 -> b == '\n' ? 2 : (b == '\r' ? 1 : 0);
                case 2 -> b == '\r' ? 3 : 0;
                default -> b == '\n' ? 4 : 0;
            };
            if (state == 4) {
                return head.toString(StandardCharsets.UTF_8);
            }
        }
        throw new IOException("HTTP 响应头超过 16KB 仍未结束");
    }

    private static Map<String, String> parseHeaders(String raw) {
        Map<String, String> headers = new LinkedHashMap<>();
        String[] lines = raw.split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT),
                        lines[i].substring(colon + 1).trim());
            }
        }
        return headers;
    }

    private void writeWebSocketFrame(int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x80 | opcode);                    // FIN + opcode
        int maskBit = 0x80;                            // 客户端到服务端必须掩码
        if (payload.length < 126) {
            frame.write(maskBit | payload.length);
        } else if (payload.length <= 0xFFFF) {
            frame.write(maskBit | 126);
            frame.writeBytes(ByteBuffer.allocate(2).putShort((short) payload.length).array());
        } else {
            frame.write(maskBit | 127);
            frame.writeBytes(ByteBuffer.allocate(8).putLong(payload.length).array());
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        frame.writeBytes(mask);
        byte[] masked = payload.clone();
        unmask(masked, mask);
        frame.writeBytes(masked);

        out.write(frame.toByteArray());
        out.flush();
    }

    private static void unmask(byte[] data, byte[] mask) {
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (data[i] ^ mask[i % 4]);
        }
    }

    /** 读满 {@code n} 字节；第一个字节就遇到 EOF 时返回 null（= 对端正常关闭）。 */
    private byte[] readFully(int n) throws IOException {
        byte[] buf = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(buf, read, n - read);
            if (r < 0) {
                if (read == 0) {
                    return null;
                }
                throw new EOFException("连接在读完 " + read + "/" + n + " 字节时就断了");
            }
            read += r;
        }
        return buf;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 关闭失败对测试没有意义
        }
    }

    /** 本地端口，用于日志定位是哪条连接。 */
    public int localPort() {
        return socket.getLocalPort();
    }
}
