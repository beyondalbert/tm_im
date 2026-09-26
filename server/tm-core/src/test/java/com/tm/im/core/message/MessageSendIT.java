package com.tm.im.core.message;

import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.domain.enums.MessageType;
import com.tm.im.storage.repository.ConversationRepositoryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 消息写入的真实服务验证：<b>真实 MySQL（经 ShardingSphere 分片）+ 真实 Redis</b>。
 *
 * <p><b>为什么单测不够</b>：{@code MessageServiceTest} 用替身验证的是<b>规则</b>
 * （哪个错误码、有没有副作用）；这里验证的是<b>规则在真实存储上的后果</b>，
 * 而两者能出的错完全不同：
 * <ul>
 *   <li>「先取号再判重」在替身上只是多一次计数，在真实 Redis 上却会留下永久空洞；</li>
 *   <li>「撞主键后自愈」在替身上是我自己写的分支，在真库上要真的撞到
 *       {@code PRIMARY (conv_id, seq)} 再真的插进去；</li>
 *   <li>消息到底落在哪张物理表、幂等唯一索引在分片下是否仍然生效，
 *       只有真库能回答（DESIGN §8.5）。</li>
 * </ul>
 *
 * <p>四个用例分别对应写路径上四个「一旦错了就无法补救」的点：
 * 落库与推送、幂等重放不消耗号、Redis 被清空后的自愈、以及被拒绝的请求不留痕。
 *
 * <p>清理按 id 精确删除（消息按 conv_id 删，会话/成员/好友/账号按 id 删），
 * 不做 TRUNCATE / FLUSHDB——这是开发库，不是我的库。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = MessageItConfig.class)
class MessageSendIT {

    /** 本次运行的 id 段：与库里既有数据天然不重叠。 */
    private static final AtomicLong ID_SEQ = new AtomicLong(System.nanoTime() * 1000L);

    @Autowired
    private MessageService messageService;

    @Autowired
    private RecordingPushPort push;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private StringRedisTemplate redis;

    private long convId;
    private long alice;
    private long bob;

    @BeforeEach
    void seed() throws SQLException {
        convId = ID_SEQ.incrementAndGet();
        alice = ID_SEQ.incrementAndGet();
        bob = ID_SEQ.incrementAndGet();

        try (Connection conn = dataSource.getConnection()) {
            insertActor(conn, alice, "it_alice_" + alice);
            insertActor(conn, bob, "it_bob_" + bob);
            insertDirectConversation(conn, convId, alice, bob);
            insertMember(conn, convId, alice);
            insertMember(conn, convId, bob);
            insertFriendship(conn, alice, bob);
        }
        push.setOnline(bob);
        push.reset();
    }

    @AfterEach
    void cleanUp() throws SQLException {
        redis.delete(ConversationRepositoryImpl.SEQ_KEY_PREFIX + convId);
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            // 顺序：先消息与成员（引用会话），再关系与账号。
            st.executeUpdate("DELETE FROM message WHERE conv_id = " + convId);
            st.executeUpdate("DELETE FROM conversation_member WHERE conv_id = " + convId);
            st.executeUpdate("DELETE FROM conversation WHERE id = " + convId);
            st.executeUpdate("DELETE FROM friendship WHERE actor_a = " + Math.min(alice, bob)
                    + " AND actor_b = " + Math.max(alice, bob));
            st.executeUpdate("DELETE FROM actor WHERE id IN (" + alice + "," + bob + ")");
        }
    }

    // ------------------------------------------------------------ 用例

    @Test
    @DisplayName("M3 验收：消息落在逻辑表 message 里（分片路由生效），并按 seq 递增")
    void messageIsPersistedThroughShardingAndSeqIncrements() {
        MessageService.SendOutcome first = send("你好", "c-1");
        MessageService.SendOutcome second = send("在吗", "c-2");

        assertThat(first.message().getSeq()).isEqualTo(1L);
        assertThat(second.message().getSeq()).isEqualTo(2L);
        assertThat(first.message().getId()).as("message_id 由 Snowflake 生成").isPositive();
        assertThat(first.message().getCreatedAt()).as("created_at 必须落库").isNotNull();

        assertThat(seqsInDb()).containsExactly(1L, 2L);
        assertThat(push.seqsTo(bob)).as("对方应当收到两次推送").containsExactly(1L, 2L);
        assertThat(seqsToSelf()).as("发送者不该收到自己的推送").isEmpty();
    }

    @Test
    @DisplayName("幂等重放：同一 client_msg_id 只落一条，且不消耗序号（Redis 计数不前进）")
    void replayIsIdempotentAndDoesNotConsumeSeq() {
        MessageService.SendOutcome first = send("你好", "c-same");
        String seqKey = ConversationRepositoryImpl.SEQ_KEY_PREFIX + convId;
        String counterAfterFirst = redis.opsForValue().get(seqKey);
        int pushesAfterFirst = push.pushCount();

        MessageService.SendOutcome replay = send("你好", "c-same");

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.message().getSeq()).isEqualTo(first.message().getSeq());
        assertThat(seqsInDb()).as("重放不能插出第二条").containsExactly(1L);
        assertThat(redis.opsForValue().get(seqKey))
                .as("重放不能动序号源：取号即消耗，会留下永久空洞")
                .isEqualTo(counterAfterFirst);
        assertThat(push.pushCount()).as("重放不能重复推送").isEqualTo(pushesAfterFirst);
    }

    @Test
    @DisplayName("Redis 被清空后序号从 1 重来 → 撞主键 → 自愈重试成功，且不再有重复 seq")
    void redisFlushTriggersSelfHeal() {
        for (int i = 0; i < 5; i++) {
            send("第" + (i + 1) + "条", "c-" + i);
        }
        assertThat(seqsInDb()).containsExactly(1L, 2L, 3L, 4L, 5L);

        // 模拟 Redis 被清空 / 无持久化重启 / 故障切换到空实例
        redis.delete(ConversationRepositoryImpl.SEQ_KEY_PREFIX + convId);

        // 不修的话：下一个号是 1，插库必然撞 PRIMARY (conv_id, seq)，
        // 而客户端只会看到「发出去没反应」。
        MessageService.SendOutcome afterFlush = send("清空之后", "c-after-flush");

        assertThat(afterFlush.message().getSeq())
                .as("自愈必须把序号抬到库内最大 seq 之上")
                .isEqualTo(6L);
        List<Long> all = seqsInDb();
        assertThat(all).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(new HashSet<>(all)).as("序号不许重复（重复 = 有消息插不进去）").hasSize(all.size());
        // 自愈是异常路径，它必须留下痕迹——否则运维永远不知道 Redis 曾经丢过数据
        assertThat(afterFlush.replayed()).isFalse();
    }

    @Test
    @DisplayName("非好友单聊：回 40003，且库里没有消息、Redis 里没有为它消耗号")
    void nonFriendIsRejectedWithoutSideEffects() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM friendship WHERE actor_a = " + Math.min(alice, bob)
                    + " AND actor_b = " + Math.max(alice, bob));
        }

        assertThatThrownBy(() -> send("你好", "c-nonfriend"))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.NOT_FRIENDS);

        assertThat(seqsInDb()).isEmpty();
        assertThat(redis.opsForValue().get(ConversationRepositoryImpl.SEQ_KEY_PREFIX + convId))
                .as("被拒绝的请求不该消耗序号")
                .isNull();
    }

    @Test
    @DisplayName("已读上报：真库上只前进不后退，且拒绝越过最大 seq 的游标")
    void readCursorOnlyMovesForward() {
        send("一", "r-1");
        send("二", "r-2");

        assertThat(messageService.markRead(convId, alice, 2L)).isEqualTo(2L);
        assertThat(lastReadSeq(alice)).isEqualTo(2L);

        // 乱序到达的旧请求不会把游标拉回去（否则未读数会凭空变大）
        messageService.markRead(convId, alice, 1L);
        assertThat(lastReadSeq(alice)).isEqualTo(2L);

        assertThatThrownBy(() -> messageService.markRead(convId, alice, 99L))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_CURSOR);
        assertThat(lastReadSeq(alice)).isEqualTo(2L);
    }

    // ------------------------------------------------------------ 辅助

    private MessageService.SendOutcome send(String text, String clientMsgId) {
        return messageService.send(new MessageService.SendCommand(
                convId, alice, clientMsgId, MessageType.TEXT,
                "{\"text\":\"" + text + "\"}", 0, true));
    }

    private List<Long> seqsInDb() {
        List<Long> seqs = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT seq FROM message WHERE conv_id = ? ORDER BY seq")) {
            ps.setLong(1, convId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    seqs.add(rs.getLong(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询消息失败", e);
        }
        return seqs;
    }

    private List<Long> seqsToSelf() {
        return push.seqsTo(alice);
    }

    private long lastReadSeq(long actorId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT last_read_seq FROM conversation_member WHERE conv_id = ? AND actor_id = ?")) {
            ps.setLong(1, convId);
            ps.setLong(2, actorId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询已读游标失败", e);
        }
    }

    private static void insertActor(Connection conn, long id, String handle) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO actor (id, actor_type, handle, display_name, status, created_at)"
                        + " VALUES (?,1,?,?,1,NOW(3))")) {
            ps.setLong(1, id);
            ps.setString(2, handle);
            ps.setString(3, handle);
            ps.executeUpdate();
        }
    }

    private static void insertDirectConversation(Connection conn, long convId, long a, long b)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO conversation (id, conv_type, title, seq_counter, pair_key, created_at)"
                        + " VALUES (?,1,NULL,0,?,NOW(3))")) {
            ps.setLong(1, convId);
            // pair_key 是单聊去重键（min_max），库里对它有唯一索引——真实的建会话流程也写它
            ps.setString(2, Math.min(a, b) + "_" + Math.max(a, b));
            ps.executeUpdate();
        }
    }

    private static void insertMember(Connection conn, long convId, long actorId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO conversation_member (conv_id, actor_id, role, last_read_seq, muted, joined_at)"
                        + " VALUES (?,?,3,0,0,NOW(3))")) {
            ps.setLong(1, convId);
            ps.setLong(2, actorId);
            ps.executeUpdate();
        }
    }

    private static void insertFriendship(Connection conn, long a, long b) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO friendship (actor_a, actor_b, status, initiator, updated_at)"
                        + " VALUES (?,?,2,?,NOW(3))")) {
            ps.setLong(1, Math.min(a, b));
            ps.setLong(2, Math.max(a, b));
            ps.setLong(3, a);
            ps.executeUpdate();
        }
    }
}
