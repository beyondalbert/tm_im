package com.tm.im.channel.server;

import com.tm.im.channel.cluster.ClusterAwareConnectionRegistry;
import com.tm.im.channel.cluster.RecordingActorRouteTable;
import com.tm.im.channel.codec.MessageMapper;
import com.tm.im.channel.config.ChannelConfiguration;
import com.tm.im.channel.config.NettyProperties;
import com.tm.im.channel.registry.LocalConnectionRegistry;
import com.tm.im.channel.support.InMemoryIdentity;
import com.tm.im.channel.support.InMemoryMessagePort;
import com.tm.im.channel.wire.RawClient;
import com.tm.im.channel.wire.RawProto;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.core.message.MessageService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * M2 验收：<b>两个客户端经 WebSocket 连通，并能各自收发 PING/PONG</b>
 * （DESIGN §14 的 M2 验收标准）。
 *
 * <p>这个测试跑的是<b>完整链路</b>：真实 TCP 端口 → 协议分流 → 编解码 →
 * 限流 → 握手鉴权（走业务线程池）→ 连接注册表 → 心跳。没有任何一处用
 * {@code EmbeddedChannel} 代替，因此它能发现那些只在真连接下出现的问题
 * （握手超时任务没被取消、顶号时把新连接误删、WS 帧没加掩码等）。
 *
 * <p><b>客户端是独立实现</b>（{@link RawClient} + {@link RawProto}）：
 * 手写 protobuf 字节、手写 RFC 6455 帧与握手、连 {@code Sec-WebSocket-Accept}
 * 都自己算。若两端共用 protobuf-java，服务端把字段号写错时客户端会以同样的
 * 错误去构造，测试依旧是绿的 —— 那种「验证」没有意义。
 *
 * <p>数据库用 {@link InMemoryIdentity}（真实仓储接口的内存实现），
 * 因此本测试既不依赖外部 MySQL，也不会在缺配置时静默跳过。
 */
class TwoClientEndToEndTest {

    // ------------------------------------------------------------------
    // proto/transport.proto 的字段号与命令字。这里刻意全部写死成字面量，
    // 而不是引用生成的 Java 常量：一旦 proto 被改动，这个测试应当失败，
    // 而不是跟着一起改（改写的是「线上兼容性」这份对外契约）。
    //
    // 引用一律写消息名，不写 proto 的行号：行号会随着注释增删而失效，
    // 而失效的行号比没有行号更坏——它会把人带到错误的定义上。
    // ------------------------------------------------------------------
    private static final int CMD_AUTH = 1;
    private static final int CMD_AUTH_OK = 2;
    private static final int CMD_PING = 3;
    private static final int CMD_PONG = 4;
    private static final int CMD_SEND = 10;
    private static final int CMD_SEND_ACK = 11;
    private static final int CMD_PUSH = 12;
    private static final int CMD_READ = 13;
    private static final int CMD_SYNC = 14;

    /** 续传整轮结束。{@code req_id} 与对应的 {@code CMD_SYNC} 相同。 */
    private static final int CMD_SYNC_END = 15;

    /**
     * 续传响应。M3 之前它是 14（与请求同号，见 04-realtime.md §2.3 的修正记录），
     * 现在固定为 16 —— 这个字面量就是协议的锁：谁把它改回 14 或改成别的值，这里会失败。
     */
    private static final int CMD_SYNC_RESP = 16;

    private static final int CMD_KICK = 20;
    private static final int CMD_ERROR = 21;

    /** Frame（proto: Frame）。 */
    private static final int F_CMD = 1;
    private static final int F_REQ_ID = 2;
    private static final int F_PAYLOAD = 3;

    /** AuthResponse（proto: AuthResponse）。 */
    private static final int AR_ACTOR_ID = 1;
    private static final int AR_HANDLE = 2;
    private static final int AR_ACTOR_TYPE = 3;
    private static final int AR_HEARTBEAT_SEC = 4;

    /** SendAck（proto: SendAck）。 */
    private static final int SA_CONV_ID = 1;
    private static final int SA_SEQ = 2;
    private static final int SA_MESSAGE_ID = 3;
    private static final int SA_CREATED_AT_MS = 4;
    private static final int SA_CLIENT_MSG_ID = 5;

    /** PushMessage（proto: PushMessage）。 */
    private static final int PM_MESSAGE = 1;

    /** Message（proto: Message）。 */
    private static final int M_MESSAGE_ID = 1;
    private static final int M_CONV_ID = 2;
    private static final int M_SEQ = 3;
    private static final int M_SENDER_ID = 4;
    private static final int M_MSG_TYPE = 5;
    private static final int M_CONTENT_JSON = 6;
    private static final int M_CREATED_AT_MS = 8;

    /** ConvCursor（proto: ConvCursor）。 */
    private static final int CC_CONV_ID = 1;
    private static final int CC_SINCE_SEQ = 2;

    /** SyncRequest（proto: SyncRequest）。 */
    private static final int SQ_CURSORS = 1;
    private static final int SQ_LIMIT = 2;

    /** SyncResponse（proto: SyncResponse）。 */
    private static final int SR_MESSAGES = 1;
    private static final int SR_HAS_MORE = 2;
    private static final int SR_TRUNCATED = 3;

    /** SyncEnd（proto: SyncEnd）。 */
    private static final int SE_OK = 1;
    private static final int SE_MESSAGE = 2;
    private static final int SE_CONV_SYNCED = 3;

    /** KickNotice（proto: KickNotice）。 */
    private static final int KN_REASON = 1;
    private static final int KN_DETAIL = 2;

    /** ErrorFrame（proto: ErrorFrame）。 */
    private static final int ER_CODE = 1;
    private static final int ER_MESSAGE = 2;
    private static final int ER_RETRYABLE = 3;

    private static final int ACTOR_TYPE_HUMAN = 1;
    private static final int ACTOR_TYPE_AGENT = 2;
    private static final int KICK_REASON_OTHER_DEVICE = 1;

    private static final int ERR_UNAUTHORIZED = 40101;
    private static final int ERR_INVALID_TOKEN_FORMAT = 40102;
    private static final int ERR_INVALID_API_KEY = 40105;
    private static final int ERR_BAD_REQUEST = 40000;
    private static final int ERR_MISSING_PARAMETER = 40001;
    private static final int ERR_INVALID_PARAMETER = 40002;
    private static final int ERR_NOT_FRIENDS = 40003;
    private static final int ERR_INTERNAL_ERROR = 50000;
    private static final int ERR_DATABASE_UNAVAILABLE = 50001;

    private static final String HOST = "127.0.0.1";
    private static final String WS_PATH = "/ws";

    /** 32 字节是 JwtTokenService 的下限，这里正好越过去一点。 */
    private static final String JWT_SECRET = "e2e-jwt-secret-must-be-at-least-32-bytes!!";

    private static final long ALICE = 1001L;
    private static final long BOT = 2002L;    private static final String BOT_API_KEY = "sk_e2e_0123456789abcdef0123456789abcdef";

    /** 握手超时：故意调到 600ms，让「不发 AUTH 就被断开」可以真实地测到。 */
    private static final long AUTH_TIMEOUT_MS = 600L;

    /** 所有 socket 读超时。取值远大于任何一次真实往返，只在服务端漏回时触发。 */
    private static final int SOCKET_TIMEOUT_MS = 8_000;

    // ------------------------------------------------------------------
    // 续传用例专用的会话 id。
    //
    // 不能用 SEND/READ 用例那个 1001：InMemoryMessagePort 是**跨用例共享**的
    // （它扮演的是存储，reset() 清的是“调用记录”而不是“数据”），
    // 共用一个会话会让续传把别的用例刚发的消息也一起补下来——
    // 断言随即变成「取决于用例执行顺序」。
    // ------------------------------------------------------------------
    private static final long CONV_SYNC = 3101L;
    private static final long CONV_ROUNDS = 3102L;

    private static NettyProperties properties;
    private static InMemoryIdentity identity;
    private static LocalConnectionRegistry registry;
    private static RecordingActorRouteTable routeTable;
    private static ThreadPoolExecutor businessExecutor;
    private static InMemoryMessagePort messages;
    private static NettyServer server;
    private static int port;

    @BeforeAll
    static void startServer() throws Exception {
        identity = new InMemoryIdentity(JWT_SECRET)
                .human(ALICE, "alice")
                .agent(BOT, "bot-alpha")
                .apiKey(BOT_API_KEY, BOT);

        properties = new NettyProperties();
        properties.setPort(0);                    // 0 = 由系统分配空闲端口，避免与真实实例撞车
        properties.setBusinessThreads(2);
        properties.setAuthTimeoutMs(AUTH_TIMEOUT_MS);
        properties.setHeartbeatIdleSeconds(30);
        properties.setHeartbeatTimeoutSeconds(90);
        properties.setMaxFramesPerSecond(1_000);

        registry = new LocalConnectionRegistry();
        // 集群路由用替身（不连 Redis）：本类验证的是「路由的发布/释放时机跟随连接生命周期」，
        // 而「Redis 上的键长什么样」由 ClusterRedisIT 用真实 Redis 验证。
        routeTable = new RecordingActorRouteTable("e2e-node");
        businessExecutor = new ChannelConfiguration().nettyBusinessExecutor(properties);
        messages = new InMemoryMessagePort();

        server = new NettyServer(properties, identity.service(),
                new ClusterAwareConnectionRegistry(registry, routeTable), businessExecutor,
                messages, java.time.ZoneId.of("Asia/Shanghai"),
                new MessageMapper(java.time.ZoneId.of("Asia/Shanghai")));
        server.start();
        port = server.port();
        assertThat(port).as("服务端必须真的绑定了一个端口").isPositive();
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) {
            server.stop();
        }
        if (businessExecutor != null) {
            businessExecutor.shutdownNow();
            businessExecutor.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    // ------------------------------------------------------------------
    // M2 验收本体
    // ------------------------------------------------------------------

    @Test
    @DisplayName("两个 WebSocket 客户端各自连通，并能独立收发 PING/PONG（M2 验收）")
    void twoWebSocketClientsExchangePingPong() throws Exception {
        try (RawClient alice = webSocket(); RawClient bot = webSocket()) {

            Map<Integer, Object> aliceAuth = authenticate(alice, 1, identity.jwt(ALICE), "web-1");
            assertThat(RawProto.number(aliceAuth, AR_ACTOR_ID)).isEqualTo(ALICE);
            assertThat(RawProto.text(aliceAuth, AR_HANDLE)).isEqualTo("alice");
            assertThat(RawProto.number(aliceAuth, AR_ACTOR_TYPE)).isEqualTo(ACTOR_TYPE_HUMAN);
            assertThat(RawProto.number(aliceAuth, AR_HEARTBEAT_SEC)).isEqualTo(30);

            Map<Integer, Object> botAuth = authenticate(bot, 1, BOT_API_KEY, "sdk-1");
            assertThat(RawProto.number(botAuth, AR_ACTOR_ID)).isEqualTo(BOT);
            assertThat(RawProto.text(botAuth, AR_HANDLE)).isEqualTo("bot-alpha");
            assertThat(RawProto.number(botAuth, AR_ACTOR_TYPE)).isEqualTo(ACTOR_TYPE_AGENT);

            // 两个客户端都在注册表里 —— 这是「互相能收到推送」的前提
            assertThat(registry.isOnline(ALICE)).isTrue();
            assertThat(registry.isOnline(BOT)).isTrue();

            // 各自收发：PONG 的 req_id 必须原样回传，这是客户端配对响应的唯一依据
            assertPingPong(alice, 7);
            assertPingPong(bot, 42);

            // 交叉发送，证明响应不会被投递到另一条连接上
            alice.send(CMD_PING, 100, null);
            bot.send(CMD_PING, 200, null);
            alice.send(CMD_PING, 101, null);

            assertThat(RawProto.number(alice.expectFrame("PING 100"), F_REQ_ID)).isEqualTo(100);
            assertThat(RawProto.number(bot.expectFrame("PING 200"), F_REQ_ID)).isEqualTo(200);
            assertThat(RawProto.number(alice.expectFrame("PING 101"), F_REQ_ID)).isEqualTo(101);

            // 双向：客户端也要能应答服务端的探活 PING（这里由客户端代服务端发起，语义等价）
            alice.send(CMD_PONG, 0, null);
            assertPingPong(bot, 300);
        }
    }

    @Test
    @DisplayName("PONG 的线上字节是 proto3 规范编码：0 值不上线，字段按序号升序")
    void pongBytesFollowCanonicalProto3Encoding() throws Exception {
        try (RawClient alice = webSocket()) {
            authenticate(alice, 1, identity.jwt(ALICE), "web-1");

            // Frame{cmd=4, req_id=7} → 08 04（cmd）10 07（req_id）
            alice.send(CMD_PING, 7, null);
            assertThat(alice.expectFrameBytes("PING req_id=7"))
                    .isEqualTo(new byte[]{0x08, 0x04, 0x10, 0x07});

            // Frame{cmd=4, req_id=0} → 只有 08 04：proto3 的 0 值字段不上线。
            // 这条断言的价值在于它同时约束了「服务端不要为了省事而写死 req_id」。
            alice.send(CMD_PING, 0, null);
            assertThat(alice.expectFrameBytes("PING req_id=0"))
                    .isEqualTo(new byte[]{0x08, 0x04});
        }
    }

    @Test
    @DisplayName("原生 TCP 承载的语义与 WebSocket 完全一致（同一个端口，靠首字节分流）")
    void rawTcpCarriesTheSameSemantics() throws Exception {
        try (RawClient alice = tcp()) {
            Map<Integer, Object> auth = authenticate(alice, 9, identity.jwt(ALICE), "cli-1");
            assertThat(RawProto.number(auth, AR_ACTOR_ID)).isEqualTo(ALICE);
            assertThat(RawProto.text(auth, AR_HANDLE)).isEqualTo("alice");
            assertThat(RawProto.number(auth, AR_HEARTBEAT_SEC)).isEqualTo(30);

            assertPingPong(alice, 11);
            assertThat(registry.isOnline(ALICE)).isTrue();
        }
    }

    @Test
    @DisplayName("同一账号第二次登录：旧连接收到 KICK(OTHER_DEVICE) 并被断开，新连接不受影响")
    void secondConnectionKicksTheFirst() throws Exception {
        try (RawClient old = webSocket(); RawClient fresh = tcp()) {
            authenticate(old, 1, identity.jwt(ALICE), "web-1");

            authenticate(fresh, 1, identity.jwt(ALICE), "web-2");

            Map<Integer, Object> kick = old.expectFrame("顶号通知");
            assertThat(RawProto.number(kick, F_CMD)).isEqualTo(CMD_KICK);
            Map<Integer, Object> notice = RawProto.parse(RawProto.bytes(kick, F_PAYLOAD));
            assertThat(RawProto.number(notice, KN_REASON)).isEqualTo(KICK_REASON_OTHER_DEVICE);
            assertThat(RawProto.text(notice, KN_DETAIL)).isNotBlank();

            // 只发 KICK 不断开是错的：客户端会不知道该不该重连
            assertThat(old.isClosedByPeer()).as("顶号后旧连接必须被关闭").isTrue();

            // 关键：旧连接的 channelInactive 不能把新连接从注册表里摘掉
            assertThat(registry.isOnline(ALICE)).isTrue();
            assertPingPong(fresh, 5);
        }
    }

    @Test
    @DisplayName("首帧不是 AUTH 就发 PING：回 40101 并断开（不允许被 PONG 变相鼓励）")
    void firstFrameMustBeAuth() throws Exception {
        try (RawClient client = tcp()) {
            client.send(CMD_PING, 1, null);

            Map<Integer, Object> error = errorFrame(client.expectFrame("首帧违规的 ERROR"));
            assertThat(RawProto.number(error, ER_CODE)).isEqualTo(ERR_UNAUTHORIZED);
            assertThat(RawProto.flag(error, ER_RETRYABLE)).isFalse();
            assertThat(client.isClosedByPeer()).isTrue();
        }
    }

    @Test
    @DisplayName("只连不发：服务端在握手死线到点后主动断开（此时不能回任何字节：承载方式未知）")
    void silentConnectionIsClosedAtTheHandshakeDeadline() throws Exception {
        try (RawClient client = tcp()) {
            long start = System.nanoTime();

            // 一个字节都没发出去，因此收到的应该是「干净的 EOF」而不是错误帧：
            // 分流尚未发生，服务端无法保证对方能理解它发出的任何字节。
            assertThat(client.receive()).as("不应该收到任何帧").isNull();

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMs)
                    .as("应当接近配置的 %dms", AUTH_TIMEOUT_MS)
                    .isGreaterThanOrEqualTo(AUTH_TIMEOUT_MS - 50)
                    .isLessThan(4_000);
        }
    }

    @Test
    @DisplayName("已分流但迟迟不发 AUTH：回 40101 再断开，总时长仍是同一个预算（不是两倍）")
    void handshakeTimeoutAfterFirstByte() throws Exception {
        try (RawClient client = tcp()) {
            long start = System.nanoTime();

            // 只发一个字节：长度前缀的首字节 0x00 → 分流成原生 TCP，但解码器凑不齐
            // 一个完整的帧，服务端因此只能等 AUTH。它必须自己把连接收尾。
        client.sendRawBytes(new byte[]{0x00});

            Map<Integer, Object> error = errorFrame(client.expectFrame("握手超时的 ERROR"));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(RawProto.number(error, ER_CODE)).isEqualTo(ERR_UNAUTHORIZED);
            // 关键：此时刻不从「第一个字节到达」重新计时，否则总时长会变成 2 倍，
            // 而 04-realtime.md §5.2 对外承诺的只有一份预算。
            assertThat(elapsedMs)
                    .as("总预算应当仍是 %dms 级别", AUTH_TIMEOUT_MS)
                    .isLessThan((long) (AUTH_TIMEOUT_MS * 1.6));
            assertThat(client.isClosedByPeer()).isTrue();
        }
    }

    @Test
    @DisplayName("凭证错误时先回错误码再断开：40102（格式错）与 40105（api_key 无效）")
    void badCredentialsGetSpecificCodesThenClose() throws Exception {
        try (RawClient malformed = webSocket()) {
            malformed.send(CMD_AUTH, 3, RawProto.authRequest("this-is-not-a-jwt", "e2e-1.0", "web-1"));
            Map<Integer, Object> error = errorFrame(malformed.expectFrame("格式错误的 token"));
            assertThat(RawProto.number(error, ER_CODE)).isEqualTo(ERR_INVALID_TOKEN_FORMAT);
            assertThat(malformed.isClosedByPeer()).isTrue();
        }

        try (RawClient revoked = webSocket()) {
            // sk_ 前缀 → 走 api_key 分支 → 按 sha256 反查 → 查不到
            String unknownKey = "sk_e2e_ffffffffffffffffffffffffffffffff";
            revoked.send(CMD_AUTH, 4, RawProto.authRequest(unknownKey, "e2e-1.0", "sdk-9"));
            Map<Integer, Object> error = errorFrame(revoked.expectFrame("无效的 api_key"));
            assertThat(RawProto.number(error, ER_CODE)).isEqualTo(ERR_INVALID_API_KEY);
            // 错误信息里绝不能回显密钥本身
            assertThat(RawProto.text(error, ER_MESSAGE)).doesNotContain(unknownKey.substring(3));
            assertThat(revoked.isClosedByPeer()).isTrue();
        }
    }

    @Test
    @DisplayName("已就绪连接：重复 AUTH 回 40000；续传请求本身不合法时也回明确的错")
    void readyConnectionAnswersProtocolMistakes() throws Exception {
        try (RawClient alice = webSocket()) {
            authenticate(alice, 1, identity.jwt(ALICE), "web-1");

            // 重复 AUTH：客户端协议实现有问题，必须让它立刻知道
            alice.send(CMD_AUTH, 2, RawProto.authRequest(identity.jwt(ALICE), "e2e-1.0", "web-1"));
            Map<Integer, Object> duplicate = errorFrame(alice.expectFrame("重复 AUTH"));
            assertThat(RawProto.number(duplicate, ER_CODE)).isEqualTo(ERR_BAD_REQUEST);

            // 续传的读取路径已经实现（见本类的几个续传用例），所以这里测的是「翻译层」：
            // 业务侧的错误码必须原样出现在错误帧里，而不是被包装成 50000。
            // 这里注的是 40001（一个游标都不带）：它是不可重试的——客户端要去改请求，
            // 而不是退避重发。（“空游标回 40001”这条规则本身在 MessageServiceTest 里验证，
            // 因为那是 MessageService 的职责；本类的内存替身不重写它。）
            messages.failNextSyncWith(ErrorCode.MISSING_PARAMETER);
            alice.send(CMD_SYNC, 3, syncRequest(1001, 0, 200));
            Map<Integer, Object> noCursor = errorFrame(alice.expectFrame("续传的参数错误帧"));
            assertThat(RawProto.number(noCursor, ER_CODE)).isEqualTo(ERR_MISSING_PARAMETER);
            assertThat(RawProto.flag(noCursor, ER_RETRYABLE)).as("4xxxx 不可盲目重试").isFalse();

            // 载荷坏掉（长度声明越界）属于客户端问题 → 40002，而不是 50000。
            // 用 50000 会让客户端去重试一个永远不会成功的请求，而它是可修的。
            alice.send(CMD_SYNC, 4, new byte[]{(byte) 0x0A, (byte) 0xFF});
            Map<Integer, Object> frame = alice.expectFrame("载荷损坏的 CMD_SYNC");
            assertThat(RawProto.number(frame, F_CMD)).isEqualTo(CMD_ERROR);
            // 注意：req_id 在 Frame 里（字段 2），不在 ErrorFrame 里。
            // 把两者混起来读会拿到 ErrorFrame 的 message 字段（长度分隔），
            // 严格解析器会当场报「字段 2 不是 varint」。
            assertThat(RawProto.number(frame, F_REQ_ID)).isEqualTo(4);
            Map<Integer, Object> broken = errorFrame(frame);
            assertThat(RawProto.number(broken, ER_CODE)).isEqualTo(ERR_INVALID_PARAMETER);

            // 连接必须还活着：请求写错不该让客户端掉线
            assertPingPong(alice, 5);
        }
    }

    @Test
    @DisplayName("M3：CMD_SYNC 补齐消息 —— 一帧 SYNC_RESP（16）后紧跟一帧 SYNC_END（15）")
    void syncDeliversTheGapAndEndsTheRound() throws Exception {
        try (RawClient alice = webSocket()) {
            authenticate(alice, 1, identity.jwt(ALICE), "web-1");
            messages.reset();

            sendText(alice, 2, CONV_SYNC, "c-1", "你好");
            sendText(alice, 3, CONV_SYNC, "c-2", "在吗");
            sendText(alice, 4, CONV_SYNC, "c-3", "明天见");
            messages.reset();

            alice.send(CMD_SYNC, 5, syncRequest(CONV_SYNC, 0, 10));

            Map<Integer, Object> respFrame = alice.expectFrame("SYNC_RESP");
            assertThat(RawProto.number(respFrame, F_CMD)).as("命令字必须是 16，不是 14").isEqualTo(CMD_SYNC_RESP);
            assertThat(RawProto.number(respFrame, F_REQ_ID)).as("req_id 必须原样回传").isEqualTo(5);
            byte[] respPayload = RawProto.bytes(respFrame, F_PAYLOAD);
            // 标量字段用 parse 读；重复字段（messages）必须走 repeatedFields —— 见 seqsOf 的注释。
            Map<Integer, Object> respScalars = RawProto.parse(respPayload);
            assertThat(RawProto.flag(respScalars, SR_HAS_MORE)).isFalse();
            assertThat(RawProto.flag(respScalars, SR_TRUNCATED))
                    .as("当前服务端不删消息，因此不能声称数据被截断")
                    .isFalse();

            List<byte[]> delivered = RawProto.repeatedFields(respPayload, SR_MESSAGES);
            assertThat(delivered).as("三条都该补下来").hasSize(3);
            assertThat(delivered.stream().map(m -> RawProto.number(RawProto.parse(m), M_SEQ)).toList())
                    .as("必须按 seq 升序下发——客户端直接按顺序处理").containsExactly(1L, 2L, 3L);
            Map<Integer, Object> first = RawProto.parse(delivered.get(0));
            assertThat(RawProto.number(first, M_CONV_ID)).isEqualTo(CONV_SYNC);
            assertThat(RawProto.number(first, M_SENDER_ID)).isEqualTo(ALICE);
            assertThat(RawProto.number(first, M_MSG_TYPE)).isEqualTo(1);   // MSG_TYPE_TEXT
            assertThat(RawProto.text(first, M_CONTENT_JSON)).contains("你好");
            assertThat(RawProto.number(first, M_CREATED_AT_MS))
                    .as("created_at_ms 必须非 0 —— 为 0 通常意味着忘了用 tm.time.zone 换算")
                    .isPositive();

            // 补齐了才发 END，而且 req_id 必须与请求相同（客户端靠它配对）
            Map<Integer, Object> endFrame = alice.expectFrame("SYNC_END");
            assertThat(RawProto.number(endFrame, F_CMD)).isEqualTo(CMD_SYNC_END);
            assertThat(RawProto.number(endFrame, F_REQ_ID)).isEqualTo(5);
            Map<Integer, Object> end = RawProto.parse(RawProto.bytes(endFrame, F_PAYLOAD));
            assertThat(RawProto.flag(end, SE_OK)).isTrue();
            assertThat(RawProto.number(end, SE_CONV_SYNCED)).isEqualTo(1);
            assertThat(RawProto.text(end, SE_MESSAGE)).as("没有跳过的会话时不该带警示文字").isEmpty();

            // 帧字段被原样翻译成调用（发送者来自已鉴权的会话，不是载荷里的东西）
            assertThat(messages.syncs).hasSize(1);
            assertThat(messages.syncs.get(0).actorId()).isEqualTo(ALICE);
            assertThat(messages.syncs.get(0).limit()).isEqualTo(10);
            assertThat(messages.syncs.get(0).cursors())
                    .extracting(MessageService.SyncCursor::convId, MessageService.SyncCursor::sinceSeq)
                    .containsExactly(tuple(CONV_SYNC, 0L));
        }
    }

    @Test
    @DisplayName("M3：has_more=true 时绝不发 SYNC_END（否则客户端以为已追平，缺的那段再没人补）")
    void syncDoesNotSendEndWhileMoreRoundsArePending() throws Exception {
        try (RawClient alice = webSocket()) {
            authenticate(alice, 1, identity.jwt(ALICE), "web-1");
            messages.reset();

            sendText(alice, 2, CONV_ROUNDS, "c-1", "一");
            sendText(alice, 3, CONV_ROUNDS, "c-2", "二");
            sendText(alice, 4, CONV_ROUNDS, "c-3", "三");
            messages.reset();

            // 第一轮：每会话只给 2 条 → 还剩一条
            alice.send(CMD_SYNC, 5, syncRequest(CONV_ROUNDS, 0, 2));
            Map<Integer, Object> firstFrame = alice.expectFrame("第一轮 SYNC_RESP");
            assertThat(RawProto.number(firstFrame, F_CMD)).isEqualTo(CMD_SYNC_RESP);
            byte[] firstPayload = RawProto.bytes(firstFrame, F_PAYLOAD);
            assertThat(RawProto.flag(RawProto.parse(firstPayload), SR_HAS_MORE)).isTrue();
            assertThat(seqsOf(firstPayload)).containsExactly(1L, 2L);

            // 第二轮：把游标推到本帧最后一条的 seq。
            // 若上一轮错发了 SYNC_END，它此刻会排在前面——req_id=5/cmd=15 会让下面两条断言当场失败。
            alice.send(CMD_SYNC, 6, syncRequest(CONV_ROUNDS, 2, 2));
            Map<Integer, Object> secondFrame = alice.expectFrame("第二轮 SYNC_RESP");
            assertThat(RawProto.number(secondFrame, F_REQ_ID))
                    .as("上一轮的 req_id 是 5：收到它就说明 has_more=true 时也发了 SYNC_END")
                    .isEqualTo(6);
            assertThat(RawProto.number(secondFrame, F_CMD)).isEqualTo(CMD_SYNC_RESP);
            byte[] secondPayload = RawProto.bytes(secondFrame, F_PAYLOAD);
            assertThat(RawProto.flag(RawProto.parse(secondPayload), SR_HAS_MORE)).isFalse();
            assertThat(seqsOf(secondPayload)).containsExactly(3L);

            Map<Integer, Object> endFrame = alice.expectFrame("SYNC_END");
            assertThat(RawProto.number(endFrame, F_CMD)).isEqualTo(CMD_SYNC_END);
            assertThat(RawProto.number(endFrame, F_REQ_ID)).as("END 关联的是本次请求").isEqualTo(6);
        }
    }

    @Test
    @DisplayName("M3：跳过的会话游标在 SYNC_END 里点名（不是静默），且不计入已补齐数")
    void syncReportsSkippedCursorsInTheEndFrame() throws Exception {
        try (RawClient alice = webSocket()) {
            authenticate(alice, 1, identity.jwt(ALICE), "web-1");
            messages.reset();

            long goneConv = 1002L;
            messages.stubNextSync(new MessageService.SyncOutcome(
                    List.of(), false, false, 0, List.of(goneConv)));

            alice.send(CMD_SYNC, 7, syncRequest(goneConv, 5, 200));

            Map<Integer, Object> respFrame = alice.expectFrame("SYNC_RESP");
            assertThat(RawProto.number(respFrame, F_CMD)).isEqualTo(CMD_SYNC_RESP);
            // 这一帧的 SyncResponse 是空的（没有消息、has_more/truncated 都是默认值），
            // 而 proto3 不编码默认值 —— 于是线上真的<b>没有 payload 字段</b>。
            // 客户端必须把「载荷缺失」当成「空的 SyncResponse」，而不是解析失败。
            byte[] respPayload = RawProto.bytes(respFrame, F_PAYLOAD);
            assertThat(respPayload == null || respPayload.length == 0)
                    .as("空 SyncResponse 在 proto3 下就是空载荷（默认值不上线）")
                    .isTrue();
            assertThat(RawProto.flag(parseOrEmpty(respPayload), SR_HAS_MORE)).isFalse();
            assertThat(RawProto.repeatedFields(respPayload, SR_MESSAGES)).isEmpty();

            Map<Integer, Object> endFrame = alice.expectFrame("SYNC_END");
            assertThat(RawProto.number(endFrame, F_CMD)).as("RESP 之后的下一帧必须是 SYNC_END").isEqualTo(CMD_SYNC_END);
            assertThat(RawProto.number(endFrame, F_REQ_ID)).isEqualTo(7);
            Map<Integer, Object> end = parseOrEmpty(RawProto.bytes(endFrame, F_PAYLOAD));
            assertThat(RawProto.flag(end, SE_OK)).isTrue();
            assertThat(RawProto.number(end, SE_CONV_SYNCED)).as("被跳过的会话不算已补齐").isZero();
            assertThat(RawProto.text(end, SE_MESSAGE))
                    .as("不说出来的话，客户端会永远带着一个已退群的游标重连，而没有任何人知道这件事")
                    .contains("1002");
        }
    }

    @Test
    @DisplayName("M3：续传失败回错误帧（可重试），而不是一帧“看着像成功”的空结果")
    void syncFailureIsReportedInsteadOfSilentSuccess() throws Exception {
        try (RawClient alice = webSocket()) {
            authenticate(alice, 1, identity.jwt(ALICE), "web-1");
            messages.reset();

            messages.failNextSyncWith(ErrorCode.DATABASE_UNAVAILABLE);
            alice.send(CMD_SYNC, 8, syncRequest(CONV_SYNC, 0, 200));

            Map<Integer, Object> frame = alice.expectFrame("SYNC 的错误帧");
            assertThat(RawProto.number(frame, F_CMD))
                    .as("失败时既不能发 RESP 也不能发 END：假的「这轮完了」会让缺失的消息永远没人知道")
                    .isEqualTo(CMD_ERROR);
            assertThat(RawProto.number(frame, F_REQ_ID)).isEqualTo(8);
            Map<Integer, Object> error = errorFrame(frame);
            assertThat(RawProto.number(error, ER_CODE)).isEqualTo(ERR_DATABASE_UNAVAILABLE);
            assertThat(RawProto.flag(error, ER_RETRYABLE)).isTrue();

            // 非业务异常（真正的服务端缺陷）则是 50000 —— 与 4xxxx 分开，
            // 因为客户端对这两类错误的处置完全不同：后者重试永远不能成功。
            messages.failNextSyncUnexpectedly();
            alice.send(CMD_SYNC, 10, syncRequest(CONV_SYNC, 0, 200));
            Map<Integer, Object> boomFrame = alice.expectFrame("SYNC 的内部错误帧");
            assertThat(RawProto.number(boomFrame, F_CMD)).isEqualTo(CMD_ERROR);
            Map<Integer, Object> boom = errorFrame(boomFrame);
            assertThat(RawProto.number(boom, ER_CODE)).isEqualTo(ERR_INTERNAL_ERROR);
            assertThat(RawProto.flag(boom, ER_RETRYABLE)).isTrue();

            assertPingPong(alice, 11);
        }
    }

    @Test
    @DisplayName("方向门禁：服务端专用命令从客户端发来 → 40000 且说清是哪一类错误")
    void serverOnlyCommandsAreRejectedAsClientMistakes() throws Exception {
        try (RawClient alice = webSocket()) {
            authenticate(alice, 1, identity.jwt(ALICE), "web-1");

            // CMD_SYNC_RESP(16)：M3 之前，CMD_SYNC 一个命令字同时充当请求与响应，
            // 于是「响应该用哪个命令字」在协议里根本没有定义（04-realtime.md §2.3）。
            alice.send(CMD_SYNC_RESP, 2, null);
            Map<Integer, Object> respFrame = alice.expectFrame("客户端发 CMD_SYNC_RESP");
            assertThat(RawProto.number(respFrame, F_CMD)).isEqualTo(CMD_ERROR);
            assertThat(RawProto.number(respFrame, F_REQ_ID)).isEqualTo(2);
            Map<Integer, Object> respError = errorFrame(respFrame);
            assertThat(RawProto.number(respError, ER_CODE)).isEqualTo(ERR_BAD_REQUEST);
            assertThat(RawProto.text(respError, ER_MESSAGE))
                    .as("必须点明「服务端专用」；含混的 unsupported cmd 会把排查方向指反")
                    .contains("CMD_SYNC_RESP")
                    .contains("服务端专用");

            // CMD_PUSH(12)：把收到的推送原样回显，是客户端实现里很常见的一种错
            byte[] pushPayload = RawProto.lenField(PM_MESSAGE, RawProto.concat(
                    RawProto.varintField(M_MESSAGE_ID, 730000000000000002L),
                    RawProto.varintField(M_CONV_ID, 1001),
                    RawProto.varintField(M_SEQ, 8)));
            alice.send(CMD_PUSH, 3, pushPayload);
            Map<Integer, Object> pushError = errorFrame(alice.expectFrame("客户端发 CMD_PUSH"));
            assertThat(RawProto.number(pushError, ER_CODE)).isEqualTo(ERR_BAD_REQUEST);
            assertThat(RawProto.text(pushError, ER_MESSAGE)).contains("CMD_PUSH");

            // 自造的编号是另一类错（协议里不存在），必须能与上面两类区分开
            alice.send(99, 4, null);
            Map<Integer, Object> unknownError = errorFrame(alice.expectFrame("自造命令字 99"));
            assertThat(RawProto.number(unknownError, ER_CODE)).isEqualTo(ERR_BAD_REQUEST);
            assertThat(RawProto.text(unknownError, ER_MESSAGE))
                    .as("未知命令字不能被说成「服务端专用命令」")
                    .contains("unsupported cmd: 99")
                    .doesNotContain("服务端专用");

            // 三类错都不该踢掉连接
            assertPingPong(alice, 5);
        }
    }

    @Test
    @DisplayName("M3：CMD_SEND 的载荷被原样翻译成调用，并回携带权威 seq 的 SEND_ACK")
    void sendCommandIsTranslatedAndAcked() throws Exception {
        try (RawClient alice = webSocket()) {
            authenticate(alice, 1, identity.jwt(ALICE), "web-1");

            byte[] sendRequest = RawProto.concat(
                    RawProto.varintField(1, 1001),                       // conv_id
                    RawProto.stringField(2, "c-7f3a9b21"),               // client_msg_id
                    RawProto.varintField(3, 1),                          // msg_type = TEXT
                    RawProto.stringField(4, "{\"text\":\"你好\"}"),   // content_json
                    RawProto.varintField(5, 0));                         // reply_to
            alice.send(CMD_SEND, 5, sendRequest);

            Map<Integer, Object> frame = alice.expectFrame("CMD_SEND_ACK");
            assertThat(RawProto.number(frame, F_CMD)).as("成功路径回的是 SEND_ACK，不是 ERROR")
                    .isEqualTo(CMD_SEND_ACK);
            assertThat(RawProto.number(frame, F_REQ_ID)).as("req_id 必须原样回传")
                    .isEqualTo(5);

            Map<Integer, Object> ack = RawProto.parse(RawProto.bytes(frame, F_PAYLOAD));
            assertThat(RawProto.number(ack, SA_CONV_ID)).isEqualTo(1001);
            assertThat(RawProto.number(ack, SA_SEQ)).as("seq 是服务端分配的权威序号").isEqualTo(1);
            assertThat(RawProto.number(ack, SA_MESSAGE_ID)).isPositive();
            assertThat(RawProto.text(ack, SA_CLIENT_MSG_ID)).isEqualTo("c-7f3a9b21");
            assertThat(RawProto.number(ack, SA_CREATED_AT_MS))
                    .as("created_at_ms 必须非 0——为 0 通常意味着忘了用 tm.time.zone 换算")
                    .isPositive();

            // 发送者是连接上鉴权得到的 actorId，而不是载荷里的任何东西：
            // 载荷里根本没有 sender 字段，但若将来有人加上并采信它，这条断言会失败。
            assertThat(messages.sent).hasSize(1);
            assertThat(messages.sent.get(0).senderId())
                    .as("发送者必须来自已鉴权的会话")
                    .isEqualTo(ALICE);
            assertThat(messages.sent.get(0).convId()).isEqualTo(1001);
            assertThat(messages.sent.get(0).clientMsgId()).isEqualTo("c-7f3a9b21");
            assertThat(messages.sent.get(0).contentJson()).contains("你好");

            messages.reset();
        }
    }

    @Test
    @DisplayName("M3：业务异常按错误码回帧（40003 不能被包装成 50000）")
    void businessErrorKeepsItsErrorCode() throws Exception {
        try (RawClient alice = webSocket()) {
            authenticate(alice, 1, identity.jwt(ALICE), "web-1");

            messages.failNextSendWith(com.tm.im.common.error.ErrorCode.NOT_FRIENDS);
            alice.send(CMD_SEND, 9, RawProto.concat(
                    RawProto.varintField(1, 1001),
                    RawProto.stringField(2, "c-nonfriend"),
                    RawProto.varintField(3, 1),
                    RawProto.stringField(4, "{\"text\":\"hi\"}")));

            Map<Integer, Object> frame = alice.expectFrame("CMD_SEND 的错误帧");
            assertThat(RawProto.number(frame, F_CMD)).isEqualTo(CMD_ERROR);
            assertThat(RawProto.number(frame, F_REQ_ID)).isEqualTo(9);
            Map<Integer, Object> error = errorFrame(frame);
            assertThat(RawProto.number(error, ER_CODE))
                    .as("业务错误码必须原样传给客户端：包装成 50000 会让客户端去重试一个永远不会成功的请求")
                    .isEqualTo(ERR_NOT_FRIENDS);
            assertThat(RawProto.flag(error, ER_RETRYABLE)).isFalse();

            // 连接还活着
            assertPingPong(alice, 10);
        }
    }

    @Test
    @DisplayName("M3：CMD_READ 送到业务层（成功时不回帧，失败必须回 ERROR）")
    void readCommandReachesService() throws Exception {
        try (RawClient alice = webSocket()) {
            authenticate(alice, 1, identity.jwt(ALICE), "web-1");

            messages.reads.clear();
            alice.send(CMD_READ, 11, RawProto.concat(
                    RawProto.varintField(1, 1001),   // conv_id
                    RawProto.varintField(2, 7)));    // last_read_seq

            // 成功没有响应帧（协议里 CMD_READ 只有请求），所以用「后续 PING 的 PONG」
            // 来确认服务端已把上一个命令处理完，而不是靠 sleep。
            assertPingPong(alice, 12);
            // 不能拿 PONG 当作「上一个命令已处理完」的证据：CMD_READ 走业务线程池，
            // 而 PING/PONG 在 IO 线程上被心跳处理器直接应答 —— 两者没有先后关系。
            // 这里等的是**副作用本身**（带超时的轮询），而不是 sleep 一拍再赌。
            awaitCondition("已读上报应被业务层处理", () -> !messages.reads.isEmpty());
            assertThat(messages.reads).as("已读上报必须带上已鉴权的 actorId，而不是载荷里的东西")
                    .containsExactly("1001:" + ALICE + "=7");

            // 失败路径：必须是错误帧，让客户端知道未读状态没有生效
            messages.failNextReadWith(com.tm.im.common.error.ErrorCode.INVALID_CURSOR);
            alice.send(CMD_READ, 13, RawProto.concat(
                    RawProto.varintField(1, 1001),
                    RawProto.varintField(2, 99999)));
            Map<Integer, Object> frame = alice.expectFrame("CMD_READ 的错误帧");
            assertThat(RawProto.number(frame, F_CMD)).isEqualTo(CMD_ERROR);
            assertThat(RawProto.number(frame, F_REQ_ID)).isEqualTo(13);
            assertThat(RawProto.number(errorFrame(frame), ER_CODE)).isEqualTo(40010);
        }
    }

    @Test
    @DisplayName("连接建立与断开都会更新注册表：断开后 isOnline 立刻为 false")
    void registryTracksConnectionLifecycle() throws Exception {
        try (RawClient bot = tcp()) {
            authenticate(bot, 1, BOT_API_KEY, "sdk-1");
            assertThat(registry.isOnline(BOT)).isTrue();
        }
        // close() 之后服务端的 channelInactive 是异步的，这里等到它生效为止
        awaitCondition("连接断开后注册表应更新", () -> !registry.isOnline(BOT));
    }

    @Test
    @DisplayName("集群路由跟随连接生命周期：鉴权成功即发布，最后一条连接断开才释放")
    void routeFollowsConnectionLifecycle() throws Exception {
        awaitCondition("上一条连接的路由应已释放（避免用例间互相影响）", () -> !routeTable.isBound(ALICE));

        try (RawClient alice = webSocket()) {
            authenticate(alice, 1, identity.jwt(ALICE), "web-1");

            // 关键：路由必须在 AUTH_OK 发出之前就位。客户端拿到 AUTH_OK 的瞬间，
            // 别的节点就可能开始因为它而发消息过来 —— 那一刻查不到路由，消息就白推一次。
            assertThat(routeTable.isBound(ALICE))
                    .as("收到 AUTH_OK 时路由必须已经发布（不能等下一次心跳或某个异步任务）")
                    .isTrue();
            assertThat(routeTable.bindCalls()).contains(ALICE);
        }

        awaitCondition("连接断开后集群路由应被释放", () -> !routeTable.isBound(ALICE));
        assertThat(routeTable.unbindCalls()).contains(ALICE);
    }

    @Test
    @DisplayName("顶号后路由必须保留给新连接：否则该账号在线、却收不到任何跨节点推送")
    void kickedConnectionKeepsTheRouteOfTheNewOne() throws Exception {
        try (RawClient old = webSocket(); RawClient fresh = tcp()) {
            authenticate(old, 1, identity.jwt(ALICE), "web-1");
            authenticate(fresh, 1, identity.jwt(ALICE), "web-2");

            old.expectFrame("顶号通知");
            assertThat(old.isClosedByPeer()).as("顶号后旧连接必须被关闭").isTrue();

            // 旧连接被关闭后，服务端会为它跑一次 channelInactive（路由的释放点就在那里）。
            // 这里断言的是「什么都不该发生」，因此没法用条件轮询去等：
            // 错误实现是同步的一次 Redis 删除（毫秒级），给足一拍就足以暴露。
            Thread.sleep(200);

            assertThat(routeTable.isBound(ALICE))
                    .as("新连接还在本节点，路由被旧连接的清理动作删掉 = 他明明在线却收不到推送")
                    .isTrue();
            assertThat(registry.isOnline(ALICE)).isTrue();
            assertPingPong(fresh, 5);
        }
    }

    @Test
    @DisplayName("Redis 抖动时路由发布失败：AUTH 仍必须成功（不能把一次 Redis 抖动放大成「全站登不上」）")
    void routePublishFailureStillAllowsLogin() throws Exception {
        routeTable.failBindAlways();
        try (RawClient bot = tcp()) {
            Map<Integer, Object> auth = authenticate(bot, 1, BOT_API_KEY, "sdk-1");
            assertThat(RawProto.number(auth, AR_ACTOR_ID)).isEqualTo(BOT);
            assertThat(registry.isOnline(BOT)).as("本地连接照常建立").isTrue();
            assertPingPong(bot, 2);
        } finally {
            // 服务端是静态共用的：不恢复的话，后面任何检查路由的用例都会红在错误的地方。
            routeTable.resetFailures();
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static RawClient webSocket() throws IOException {
        return RawClient.webSocket(HOST, port, WS_PATH, SOCKET_TIMEOUT_MS);
    }

    private static RawClient tcp() throws IOException {
        return RawClient.tcp(HOST, port, SOCKET_TIMEOUT_MS);
    }

    /** 发 AUTH 并断言收到 AUTH_OK，返回 AuthResponse 的字段表。 */
    private static Map<Integer, Object> authenticate(RawClient client, long reqId,
                                                     String token, String deviceId) throws IOException {
        client.send(CMD_AUTH, reqId, RawProto.authRequest(token, "e2e-1.0", deviceId));
        Map<Integer, Object> frame = client.expectFrame("AUTH 的响应");
        assertThat(RawProto.number(frame, F_CMD)).as("AUTH 的响应必须是 CMD_AUTH_OK").isEqualTo(CMD_AUTH_OK);
        assertThat(RawProto.number(frame, F_REQ_ID)).as("req_id 必须原样回传").isEqualTo(reqId);
        byte[] payload = RawProto.bytes(frame, F_PAYLOAD);
        assertThat(payload).as("AUTH_OK 必须带 AuthResponse 载荷").isNotNull();
        return RawProto.parse(payload);
    }

    private static void assertPingPong(RawClient client, long reqId) throws IOException {
        client.send(CMD_PING, reqId, null);
        Map<Integer, Object> frame = client.expectFrame("PING " + reqId);
        assertThat(RawProto.number(frame, F_CMD)).isEqualTo(CMD_PONG);
        assertThat(RawProto.number(frame, F_REQ_ID)).isEqualTo(reqId);
        assertThat(RawProto.bytes(frame, F_PAYLOAD)).as("PONG 不应带载荷").isNull();
    }

    /**
     * 解析一个可能缺失的载荷。
     *
     * <p><b>为什么需要它</b>：proto3 不编码默认值，所以「一个字段都没设」的消息
     * 在线上是零字节，连长度分隔字段本身都不会出现——{@code RawProto.bytes} 返回 null。
     * 这不是异常数据，而是最普通的一种（例如“本轮没有任何消息要补”的 SyncResponse）。
     * 客户端把它当解析失败的话，会在最正常的场景下报错。
     */
    private static Map<Integer, Object> parseOrEmpty(byte[] payload) {
        return payload == null ? Map.of() : RawProto.parse(payload);
    }

    /**
     * 构造 {@code SyncRequest} 载荷（04-realtime.md §6.2）：一个游标 + 每会话条数上限。
     *
     * <p>字段号写死而不是引用生成的常量：与命令字同理，proto 一变这里就该失败。
     */
    private static byte[] syncRequest(long convId, long sinceSeq, int limit) {
        byte[] cursor = RawProto.concat(
                RawProto.varintField(CC_CONV_ID, convId),
                RawProto.varintField(CC_SINCE_SEQ, sinceSeq));
        return RawProto.concat(
                RawProto.lenField(SQ_CURSORS, cursor),
                RawProto.varintField(SQ_LIMIT, limit));
    }

    /**
     * 发一条文本消息并等回 {@code SEND_ACK}。
     *
     * <p>刻意是同步等待而不只是「发出去」：后续续传断言依赖“这几条消息已经落库且 seq 已定”，
     * 不等 ACK 就发 SYNC 会让用例变成竞态（本类的另一个用例已经踩过一次这种坑）。
     */
    private static void sendText(RawClient client, long reqId, long convId,
                                 String clientMsgId, String text) throws IOException {
        client.send(CMD_SEND, reqId, RawProto.concat(
                RawProto.varintField(1, convId),                                   // conv_id
                RawProto.stringField(2, clientMsgId),                              // client_msg_id
                RawProto.varintField(3, 1),                                        // msg_type = TEXT
                RawProto.stringField(4, "{\"text\":\"" + text + "\"}")));   // content_json
        Map<Integer, Object> ack = client.expectFrame("CMD_SEND_ACK");
        assertThat(RawProto.number(ack, F_CMD)).isEqualTo(CMD_SEND_ACK);
    }

    /** 取一帧 {@code SyncResponse} 里每条消息的 seq，顺序即线上顺序。
     *
     * <p>必须走 {@link RawProto#repeatedFields}：{@code RawProto.parse} 是「字段号 → 一个值」
     * 的映射，三条消息在那里只会剩下最后一条，而断言依旧会看起来很正常。
     */
    private static List<Long> seqsOf(byte[] syncResponse) {
        return RawProto.repeatedFields(syncResponse, SR_MESSAGES).stream()
                .map(m -> RawProto.number(RawProto.parse(m), M_SEQ))
                .toList();
    }

    /** 断言是 CMD_ERROR 并返回 ErrorFrame 的字段表。 */
    private static Map<Integer, Object> errorFrame(Map<Integer, Object> frame) {
        assertThat(RawProto.number(frame, F_CMD)).as("应当是 CMD_ERROR").isEqualTo(CMD_ERROR);
        byte[] payload = RawProto.bytes(frame, F_PAYLOAD);
        assertThat(payload).as("ERROR 必须带 ErrorFrame 载荷").isNotNull();
        return RawProto.parse(payload);
    }

    private static void awaitCondition(String what, java.util.function.BooleanSupplier condition)
            throws Exception {
        ExecutorService poller = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "e2e-await");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<Boolean> future = poller.submit(() -> {
                while (!condition.getAsBoolean()) {
                    Thread.sleep(20);
                }
                return Boolean.TRUE;
            });
            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new AssertionError(what + "（5s 内未达成）");
            }
        } finally {
            poller.shutdownNow();
        }
    }
}
