package com.tm.im.core.message;

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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 续传读取路径的真实存储验证：<b>真实 MySQL（经 ShardingSphere 分片）+ 真实 Redis</b>。
 *
 * <p>它补的是 {@code MessageServiceTest} 补不了的那一半。单测用替身验证「规则」
 * （哪个错误码、has_more 怎么算），而这里验证的是<b>规则在真实存储上的后果</b>：
 * <ul>
 *   <li>{@code seq > since_seq} 在<b>分片后</b>的表上是不是真的能按升序取出来
 *       （主键是 {@code (conv_id, seq)}，所以它应当是单表聚簇索引范围扫描）；</li>
 *   <li>「多取一行判 has_more」在真库上会不会多返回一条给客户端（分页边界最容易差一）；</li>
 *   <li>多轮拉取能不能<b>终止</b>，且每条消息<b>恰好出现一次</b>（这是验收标准：一条不漏、一条不重、不会死循环）。</li>
 * </ul>
 *
 * <p>还有一个只在真库上才成立的前提：{@code seq} 允许有空洞（取号即消耗、消息被清理都会留下空洞），
 * 所以「某一行不存在」不能被当成「已被删除」。这条规则在真库上表现为
 * 「删掉中间一条之后，续传必须照常返回其余消息，且<b>不能</b>声称数据被截断」——
 * 单测里手写的替身不会替我们想到这一点。
 *
 * <p>清理按 id 精确删除（消息与会话按 conv_id 删，账号按 id 删），不做 TRUNCATE / FLUSHDB——
 * 这是开发库，不是我的库。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = MessageItConfig.class)
class MessageSyncIT {

    /** 本次运行的 id 段：与库里既有数据天然不重叠。 */
    private static final AtomicLong ID_SEQ = new AtomicLong(System.nanoTime() * 1000L);

    @Autowired
    private MessageService messageService;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private StringRedisTemplate redis;

    private long convId;
    private long alice;
    private long bob;

    /** 两个与 alice 无关的人以及他们的会话——用于验证「非成员游标被跳过且一条都不泄露」。 */
    private long strangerConvId;
    private long carol;
    private long dave;

    @BeforeEach
    void seed() throws SQLException {
        convId = ID_SEQ.incrementAndGet();
        alice = ID_SEQ.incrementAndGet();
        bob = ID_SEQ.incrementAndGet();
        strangerConvId = ID_SEQ.incrementAndGet();
        carol = ID_SEQ.incrementAndGet();
        dave = ID_SEQ.incrementAndGet();

        try (Connection conn = dataSource.getConnection()) {
            insertActor(conn, alice, "it_sync_alice_" + alice);
            insertActor(conn, bob, "it_sync_bob_" + bob);
            insertActor(conn, carol, "it_sync_carol_" + carol);
            insertActor(conn, dave, "it_sync_dave_" + dave);

            insertDirectConversation(conn, convId, alice, bob);
            insertMember(conn, convId, alice);
            insertMember(conn, convId, bob);
            insertFriendship(conn, alice, bob);

            // alice 不在这个会话里，但会话里确实有消息——「查不到」与「不该给」必须区分开。
            insertDirectConversation(conn, strangerConvId, carol, dave);
            insertMember(conn, strangerConvId, carol);
            insertMember(conn, strangerConvId, dave);
            insertMessageRow(conn, strangerConvId, 1L, carol, "{\"text\":\"外人说的话\"}");
        }
    }

    @AfterEach
    void cleanUp() throws SQLException {
        redis.delete(ConversationRepositoryImpl.SEQ_KEY_PREFIX + convId);
        redis.delete(ConversationRepositoryImpl.SEQ_KEY_PREFIX + strangerConvId);
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            for (long id : new long[]{convId, strangerConvId}) {
                st.executeUpdate("DELETE FROM message WHERE conv_id = " + id);
                st.executeUpdate("DELETE FROM conversation_member WHERE conv_id = " + id);
                st.executeUpdate("DELETE FROM conversation WHERE id = " + id);
            }
            st.executeUpdate("DELETE FROM friendship WHERE actor_a = " + Math.min(alice, bob)
                    + " AND actor_b = " + Math.max(alice, bob));
            st.executeUpdate("DELETE FROM actor WHERE id IN (" + alice + "," + bob + ","
                    + carol + "," + dave + ")");
        }
    }

    // ------------------------------------------------------------ 用例

    @Test
    @DisplayName("M3 验收：SYNC 按 seq 升序返回 since_seq 之后的消息（分片表上的范围扫描）")
    void syncReturnsExactlyTheMissingRangeInOrder() {
        for (int i = 1; i <= 5; i++) {
            send("第" + i + "条", "c-" + i);
        }

        MessageService.SyncOutcome outcome = sync(2, 200);

        assertThat(seqs(outcome)).containsExactly(3L, 4L, 5L);
        assertThat(outcome.hasMore()).isFalse();
        assertThat(outcome.truncated()).isFalse();
        assertThat(outcome.convsSynced()).isEqualTo(1);
        assertThat(outcome.skippedConvs()).isEmpty();

        // 读回来的必须是「能直接发给客户端」的完整实体：id / 内容 / 时间一个都不能少
        assertThat(outcome.messages().get(0).getId()).as("message_id 必须落库（否则客户端无法引用它）").isPositive();
        assertThat(outcome.messages().get(0).getConvId()).isEqualTo(convId);
        assertThat(outcome.messages().get(0).getSenderId()).isEqualTo(alice);
        assertThat(outcome.messages().get(0).getMsgType()).isEqualTo(MessageType.TEXT);
        assertThat(outcome.messages().get(0).getContent()).contains("第3条");
        assertThat(outcome.messages().get(0).getCreatedAt())
                .as("created_at 必须从 DATETIME 解析出来（为空会让客户端显示 1970 年）")
                .isNotNull();
    }

    @Test
    @DisplayName("M3 验收：多轮拉取会终止，且每条消息恰好出现一次（一条不漏、一条不重）")
    void pagingRoundsTerminateAndDeliverEveryMessageExactlyOnce() {
        for (int i = 1; i <= 5; i++) {
            send("第" + i + "条", "c-" + i);
        }

        List<Long> delivered = new ArrayList<>();
        long cursor = 0;
        int rounds = 0;
        boolean hasMore = true;
        while (hasMore) {
            MessageService.SyncOutcome page = sync(cursor, 2);
            // 死循环的唯一防线：只要 has_more=true，就必须至少带回一条能推进游标的消息
            assertThat(page.messages())
                    .as("has_more=true 却一条消息都没有时，客户端永远推不动游标")
                    .isNotEmpty();
            hasMore = page.hasMore();
            for (com.tm.im.domain.entity.Message m : page.messages()) {
                delivered.add(m.getSeq());
                cursor = Math.max(cursor, m.getSeq());
            }
            if (++rounds > 10) {
                throw new AssertionError("多轮拉取没有终止（每轮 2 条、共 5 条，最多 3 轮）");
            }
        }

        assertThat(rounds).as("每轮 2 条、共 5 条 → 3 轮").isEqualTo(3);
        assertThat(delivered).as("既不漏也不重，且顺序稳定").containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    @DisplayName("游标已在末尾（或超过末尾）：返回空，且不能声称数据被截断")
    void cursorAtTheEndYieldsNothingWithoutClaimingTruncation() {
        send("唯一一条", "c-1");

        MessageService.SyncOutcome atEnd = sync(1, 200);
        assertThat(atEnd.messages()).isEmpty();
        assertThat(atEnd.hasMore()).isFalse();
        assertThat(atEnd.truncated())
                .as("没有归档水位就不能说「补不齐」——那是假警报，会把客户端赶去 REST 拉同样的东西")
                .isFalse();

        MessageService.SyncOutcome beyond = sync(99, 200);
        assertThat(beyond.messages()).isEmpty();
        assertThat(beyond.hasMore()).isFalse();
        assertThat(beyond.truncated()).isFalse();
    }

    @Test
    @DisplayName("非成员的会话游标被跳过：本人相关的会话照常补齐，别人的消息一条都不返回")
    void nonMemberCursorIsSkippedWithoutLeakingItsMessages() {
        send("我的消息", "c-1");

        MessageService.SyncOutcome outcome = messageService.sync(new MessageService.SyncCommand(
                alice,
                List.of(new MessageService.SyncCursor(strangerConvId, 0),
                        new MessageService.SyncCursor(convId, 0)),
                200));

        assertThat(outcome.skippedConvs())
                .as("跳过而不是整轮回 40303：否则一个已退群的游标会让该用户永远补不了别的会话")
                .containsExactly(strangerConvId);
        assertThat(outcome.convsSynced()).isEqualTo(1);
        assertThat(outcome.messages()).extracting(com.tm.im.domain.entity.Message::getConvId)
                .as("别人的会话即使有消息，也一条都不能下发")
                .containsOnly(convId);
    }

    @Test
    @DisplayName("seq 空洞是合法的：删掉中间一条之后，续传照常返回其余消息且不声称截断")
    void seqHolesAreLegalAndAreNotReportedAsTruncation() throws SQLException {
        send("一", "c-1");
        send("二", "c-2");
        send("三", "c-3");

        // 模拟「取号即消耗」留下的空洞（或将来归档任务删掉了那一行）
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM message WHERE conv_id = " + convId + " AND seq = 2");
        }

        MessageService.SyncOutcome outcome = sync(0, 200);

        assertThat(seqs(outcome)).as("查询是「seq 大于游标」，不是「取到连续为止」").containsExactly(1L, 3L);
        assertThat(outcome.hasMore()).isFalse();
        assertThat(outcome.truncated())
                .as("「行不存在」推不出「已被删除」：把它当截断会让客户端做一次没有意义的 REST 重拉")
                .isFalse();

        // 游标落在空洞前后都必须能正常工作
        assertThat(seqs(sync(1, 200))).containsExactly(3L);
        assertThat(seqs(sync(2, 200))).containsExactly(3L);
    }

    // ------------------------------------------------------------ 辅助

    private MessageService.SyncOutcome sync(long sinceSeq, int limit) {
        return messageService.sync(new MessageService.SyncCommand(
                alice, List.of(new MessageService.SyncCursor(convId, sinceSeq)), limit));
    }

    private static List<Long> seqs(MessageService.SyncOutcome outcome) {
        return outcome.messages().stream().map(com.tm.im.domain.entity.Message::getSeq).toList();
    }

    private MessageService.SendOutcome send(String text, String clientMsgId) {
        return messageService.send(new MessageService.SendCommand(
                convId, alice, clientMsgId, MessageType.TEXT,
                "{\"text\":\"" + text + "\"}", 0, true));
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

    /**
     * 直接插一行「已是好友」（本类验的是发送/读取路径的权限规则，不是加好友流程）。
     *
     * <p>{@code request_id} 用 {@code min(a,b)} 占位：它是唯一索引，而在本类里
     * 每一对 actor 都是本次运行新建的雪花号，所以这个值全局唯一。
     * {@code expires_at} 对所有非 PENDING 的行都没有意义（列是 NOT NULL），
     * 因此与 {@code created_at} 取同一个值——这正确反映了「它只在待处理时有意义」。
     */
    private static void insertFriendship(Connection conn, long a, long b) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO friendship"
                        + " (request_id, actor_a, actor_b, status, initiator,"
                        + "  created_at, expires_at, updated_at)"
                        + " VALUES (?,?,?,2,?,NOW(3),NOW(3),NOW(3))")) {
            ps.setLong(1, Math.min(a, b));
            ps.setLong(2, Math.min(a, b));
            ps.setLong(3, Math.max(a, b));
            ps.setLong(4, a);
            ps.executeUpdate();
        }
    }

    /** 直接插一条消息行（绕开写路径）：本类要验证的是读取路径，不是「能不能发出去」。 */
    private static void insertMessageRow(Connection conn, long convId, long seq, long senderId,
                                         String contentJson) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO message (id, conv_id, seq, sender_id, msg_type, content, created_at)"
                        + " VALUES (?,?,?,?,1,?,NOW(3))")) {
            ps.setLong(1, seq * 1000 + convId % 1000);
            ps.setLong(2, convId);
            ps.setLong(3, seq);
            ps.setLong(4, senderId);
            ps.setString(5, contentJson);
            ps.executeUpdate();
        }
    }
}
