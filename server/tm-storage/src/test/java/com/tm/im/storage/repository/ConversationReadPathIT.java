package com.tm.im.storage.repository;

import com.tm.im.domain.entity.Conversation;
import com.tm.im.domain.entity.ConversationMember;
import com.tm.im.domain.entity.Message;
import com.tm.im.domain.enums.ConvType;
import com.tm.im.domain.enums.MemberRole;
import com.tm.im.domain.enums.MessageType;
import com.tm.im.domain.repository.ConversationMembership;
import com.tm.im.domain.repository.ConversationRepository;
import com.tm.im.domain.repository.MessageRepository;
import com.tm.im.storage.it.ItSpringConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「我的会话列表 / 消息历史」这两条读路径在真实 MySQL 上的行为（M3 REST §4.3–§4.7 的底座）。
 *
 * <p><b>为什么这些查询必须上真库</b>：它们全都依赖 MyBatis-Plus 的 {@code Wrapper} 拼出来的 SQL，
 * 而拼错的方式在内存替身里看不见：
 * <ul>
 *   <li>{@code .last("LIMIT n")} 之后再追加条件会拼出 {@code ... LIMIT 10 AND seq < 5}——
 *       内存替身照样"正确"，数据库直接语法错；</li>
 *   <li>{@code selectBatchIds} 在单表（{@code conversation}）上是普通 IN 查询，
 *       而在分片表上会按分片键扇出——{@code listMemberships} 的正确性依赖这个区别；</li>
 *   <li>{@code createGroup} 的「原子」是 {@code @Transactional} 给的，
 *       而<b>自调用不走代理</b>时它会静默失效（同一个坑本项目在 {@code ConversationSeqCounter}
 *       上真的踩过一次）。所以必须有一条「成员行写失败则会话行也不在」的断言。</li>
 * </ul>
 *
 * <p><b>数据清理</b>：全部按本次运行生成的 id 删除，不用 TRUNCATE / FLUSHDB，
 * 也不删任何一条不是自己造的行（这是开发库，不是测试库）。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ItSpringConfig.class)
class ConversationReadPathIT {

    /** 本次运行的 id 前缀：与库里既有数据天然不重叠，且一眼能看出是测试数据。 */
    private static final AtomicLong ID_SEQ = new AtomicLong(System.nanoTime() * 1000L);

    private static final long ALICE = ID_SEQ.get();
    private static final long BOB = ALICE + 1;
    private static final long CAROL = ALICE + 2;

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 8, 0, 0);

    @Autowired
    @Qualifier("conversationRepositoryImpl")
    private ConversationRepository conversations;

    @Autowired
    private MessageRepository messages;

    @Autowired
    private DataSource dataSource;

    private final List<Long> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long convId : created) {
            execute("DELETE FROM conversation_member WHERE conv_id = ?", convId);
            execute("DELETE FROM conversation WHERE id = ?", convId);
            // message 是分片表：这条 DELETE 带 conv_id，ShardingSphere 会精确路由到单张物理表
            execute("DELETE FROM message WHERE conv_id = ?", convId);
        }
        created.clear();
    }

    // ================================================================ 建群

    @Test
    @DisplayName("建群：会话行与成员行一次写全，角色/加入时间原样落库")
    void createGroupWritesEverythingAtomically() {
        long convId = newId();
        List<ConversationMember> rows = List.of(
                member(convId, ALICE, MemberRole.OWNER, T0),
                member(convId, BOB, MemberRole.MEMBER, T0.plusSeconds(1)),
                member(convId, CAROL, MemberRole.MEMBER, T0.plusSeconds(2)));
        conversations.createGroup(group(convId, "IT 群"), rows);

        assertThat(conversations.findById(convId)).isPresent();
        assertThat(conversations.countMembers(convId)).isEqualTo(3L);
        assertThat(conversations.listMembers(convId, 10)).extracting(ConversationMember::getActorId)
                .as("成员按加入时间升序（客户端要按这个顺序画成员列表）")
                .containsExactly(ALICE, BOB, CAROL);
        assertThat(conversations.findMember(convId, ALICE).orElseThrow().getRole())
                .isEqualTo(MemberRole.OWNER);
    }

    @Test
    @DisplayName("建群：任一成员行写失败 → 整笔回滚（不能留下一个没有成员的群）")
    void createGroupRollsBackOnMemberFailure() {
        long convId = newId();
        // 同一个 (conv_id, actor_id) 写两遍：第二行撞联合主键
        List<ConversationMember> rows = List.of(
                member(convId, ALICE, MemberRole.OWNER, T0),
                member(convId, ALICE, MemberRole.MEMBER, T0));

        assertThatThrownBy(() -> conversations.createGroup(group(convId, "会失败的群"), rows))
                .isInstanceOf(DataAccessException.class);

        assertThat(conversations.findById(convId))
                .as("成员行失败时事务必须回滚——否则客户端重试会建出第二个群，用户看到两个残缺的群")
                .isEmpty();
        assertThat(conversations.countMembers(convId)).isZero();
    }

    // ================================================================ 会话列表

    @Test
    @DisplayName("listMemberships：一次取回「会话 + 我在其中的成员行」，不含我没参与的会话")
    void listMembershipsJoinsMyMemberRow() {
        long direct = newId();
        conversations.insert(directConversation(direct, ALICE + "_" + BOB));
        conversations.addMember(direct, ALICE, MemberRole.MEMBER, T0);
        conversations.addMember(direct, BOB, MemberRole.MEMBER, T0);

        long group = newId();
        conversations.createGroup(group(group, "IT 群"), List.of(
                member(group, ALICE, MemberRole.OWNER, T0),
                member(group, BOB, MemberRole.MEMBER, T0)));

        long notMine = newId();
        conversations.insert(group(notMine, "别人的群"));
        conversations.addMember(notMine, CAROL, MemberRole.OWNER, T0);

        messages.insert(message(group, 7L, BOB, T0.plusMinutes(1)));
        conversations.updateLastReadSeq(group, ALICE, 3L);

        List<ConversationMembership> rows = conversations.listMemberships(ALICE, 0);

        assertThat(rows).extracting(r -> r.conversation().getId())
                .containsExactlyInAnyOrder(direct, group)
                .doesNotContain(notMine);
        ConversationMembership mine = rows.stream()
                .filter(r -> r.conversation().getId() == group)
                .findFirst().orElseThrow();
        assertThat(mine.member().getActorId()).isEqualTo(ALICE);
        assertThat(mine.member().getLastReadSeq())
                .as("未读数要用它算，取错人就永远是 0")
                .isEqualTo(3L);
        assertThat(mine.conversation().getTitle()).isEqualTo("IT 群");
    }

    @Test
    @DisplayName("listMemberships：成员行指向不存在的会话时跳过（数据不一致不能拖垮整个列表）")
    void listMembershipsSkipsOrphanMemberRows() {
        long real = newId();
        conversations.insert(directConversation(real, ALICE + "_" + BOB));
        conversations.addMember(real, ALICE, MemberRole.MEMBER, T0);

        long orphan = newId();
        conversations.insert(directConversation(orphan, ALICE + "_" + CAROL));
        // 把会话行删掉，只留成员行——这就是「删会话没删成员」之后的库内状态
        execute("DELETE FROM conversation WHERE id = ?", orphan);
        execute("INSERT INTO conversation_member (conv_id, actor_id, role, last_read_seq, muted, joined_at)"
                + " VALUES (?, ?, 3, 0, 0, NOW(3))", orphan, ALICE);

        List<ConversationMembership> rows = conversations.listMemberships(ALICE, 0);

        assertThat(rows).extracting(r -> r.conversation().getId()).contains(real);
        assertThat(rows).extracting(r -> r.conversation().getId()).doesNotContain(orphan);
    }

    // ================================================================ 消息历史

    @Test
    @DisplayName("findLatest / listBeforeSeq：走主键 (conv_id, seq) 的倒序取法与 SYNC 的升序取法互不干扰")
    void messageReadsFollowSeqOrder() {
        long convId = newId();
        conversations.insert(group(convId, "IT 群"));
        for (long seq = 1; seq <= 3; seq++) {
            messages.insert(message(convId, seq, BOB, T0.plusSeconds(seq)));
        }

        Message latest = messages.findLatest(convId).orElseThrow();
        assertThat(latest.getSeq()).isEqualTo(3L);
        assertThat(latest.getSenderId()).isEqualTo(BOB);
        assertThat(messages.maxSeq(convId)).as("maxSeq 与 findLatest 必须是同一次查询的两种用法")
                .isEqualTo(3L);

        assertThat(messages.listBeforeSeq(convId, 3L, 2))
                .extracting(Message::getSeq)
                .as("倒序：最新在前，且不含游标本身（翻历史不能重复最后一条）")
                .containsExactly(2L, 1L);
        assertThat(messages.listBeforeSeq(convId, 0L, 10))
                .as("无上界时从最新一条开始")
                .extracting(Message::getSeq)
                .containsExactly(3L, 2L, 1L);
        assertThat(messages.listAfterSeq(convId, 2L, 10))
                .as("同一条数据上的升序取法（SYNC / 增量拉取）")
                .extracting(Message::getSeq)
                .containsExactly(3L);
    }

    // ================================================================ 群成员管理（§4.9 的写路径）

    @Test
    @DisplayName("removeMember / updateMemberRole：命中返回 true，人不在群里返回 false")
    void memberWritesReportWhetherTheyHitARow() {
        long convId = newId();
        conversations.createGroup(group(convId, "IT 群"), List.of(
                member(convId, ALICE, MemberRole.OWNER, T0),
                member(convId, BOB, MemberRole.MEMBER, T0)));

        assertThat(conversations.updateMemberRole(convId, BOB, MemberRole.ADMIN)).isTrue();
        assertThat(conversations.findMember(convId, BOB).orElseThrow().getRole())
                .isEqualTo(MemberRole.ADMIN);
        assertThat(conversations.updateMemberRole(convId, CAROL, MemberRole.ADMIN))
                .as("没有成员行可改——调用方要回 40908 而不是静默成功")
                .isFalse();

        assertThat(conversations.removeMember(convId, BOB)).isTrue();
        assertThat(conversations.findMember(convId, BOB)).isEmpty();
        assertThat(conversations.removeMember(convId, BOB)).as("再删一次没有行可删").isFalse();
        assertThat(conversations.countMembers(convId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("transferOwnership：三行一起改；目标不是成员时抛异常并<b>整笔回滚</b>")
    void transferOwnershipIsAtomic() {
        long convId = newId();
        conversations.createGroup(group(convId, "IT 群"), List.of(
                member(convId, ALICE, MemberRole.OWNER, T0),
                member(convId, BOB, MemberRole.MEMBER, T0),
                member(convId, CAROL, MemberRole.MEMBER, T0)));

        assertThat(conversations.transferOwnership(convId, ALICE, BOB)).isTrue();
        assertThat(conversations.findMember(convId, BOB).orElseThrow().getRole())
                .isEqualTo(MemberRole.OWNER);
        assertThat(conversations.findMember(convId, ALICE).orElseThrow().getRole())
                .isEqualTo(MemberRole.ADMIN);
        assertThat(conversations.findById(convId).orElseThrow().getOwnerActor()).isEqualTo(BOB);

        // 已经不是群主了：条件式更新一行都改不到，必须回 false（而不是把新群主又降下去）
        assertThat(conversations.transferOwnership(convId, ALICE, CAROL)).isFalse();
        assertThat(conversations.findMember(convId, CAROL).orElseThrow().getRole())
                .isEqualTo(MemberRole.MEMBER);
        assertThat(conversations.findById(convId).orElseThrow().getOwnerActor()).isEqualTo(BOB);

        // ★ 目标成员行不存在：第一行已经改了，事务必须把整笔回滚。
        // 这一条盯的是 @Transactional 有没有真的经过代理生效（自调用不走代理时它会静默失效，
        // 而失效的表现是「旧群主降成了 ADMIN，新群主却没升上去」——群里一个 OWNER 都没有）。
        assertThatThrownBy(() -> conversations.transferOwnership(convId, BOB, CAROL + 999))
                .isInstanceOf(IllegalStateException.class);
        assertThat(conversations.findMember(convId, BOB).orElseThrow().getRole())
                .as("回滚后新群主仍是 OWNER")
                .isEqualTo(MemberRole.OWNER);
        assertThat(conversations.findById(convId).orElseThrow().getOwnerActor())
                .as("回滚后 owner_actor 仍是旧值")
                .isEqualTo(BOB);
    }

    @Test
    @DisplayName("updateTitle：只动 title 一列")
    void updateTitleOnlyTouchesTheTitle() {
        long convId = newId();
        conversations.createGroup(group(convId, "旧群名"), List.of(
                member(convId, ALICE, MemberRole.OWNER, T0),
                member(convId, BOB, MemberRole.MEMBER, T0)));

        assertThat(conversations.updateTitle(convId, "新群名")).isTrue();

        Conversation updated = conversations.findById(convId).orElseThrow();
        assertThat(updated.getTitle()).isEqualTo("新群名");
        assertThat(updated.getOwnerActor()).as("改群名不该动群主").isEqualTo(ALICE);
        assertThat(updated.getConvType()).isEqualTo(ConvType.GROUP);
        assertThat(conversations.countMembers(convId)).as("也不该动成员").isEqualTo(2L);
        assertThat(conversations.updateTitle(convId + 999, "没有这个会话")).isFalse();
    }

    // ================================================================ 辅助

    private static long newId() {
        return ID_SEQ.incrementAndGet();
    }

    /** 建一个群会话（同时登记到清理列表，断言失败时也不会留下脏数据）。 */
    private Conversation group(long convId, String title) {
        created.add(convId);
        Conversation conversation = new Conversation();
        conversation.setId(convId);
        conversation.setConvType(ConvType.GROUP);
        conversation.setTitle(title);
        conversation.setOwnerActor(ALICE);
        conversation.setCreatedAt(T0);
        return conversation;
    }

    private Conversation directConversation(long convId, String pairKey) {
        created.add(convId);
        Conversation conversation = new Conversation();
        conversation.setId(convId);
        conversation.setConvType(ConvType.DIRECT);
        conversation.setPairKey(pairKey);
        conversation.setCreatedAt(T0);
        return conversation;
    }

    private static ConversationMember member(long convId, long actorId, MemberRole role,
                                             LocalDateTime joinedAt) {
        ConversationMember row = new ConversationMember();
        row.setConvId(convId);
        row.setActorId(actorId);
        row.setRole(role);
        row.setLastReadSeq(0L);
        row.setMuted(false);
        row.setJoinedAt(joinedAt);
        return row;
    }

    private static Message message(long convId, long seq, long senderId, LocalDateTime createdAt) {
        Message message = new Message();
        message.setId(ID_SEQ.incrementAndGet());
        message.setConvId(convId);
        message.setSeq(seq);
        message.setSenderId(senderId);
        message.setMsgType(MessageType.TEXT);
        message.setContent("{\"text\":\"it\"}");
        message.setCreatedAt(createdAt);
        return message;
    }

    /**
     * 直接执行一条带参数的 DDL/DML。
     *
     * <p>清理与「造出数据不一致」两类操作刻意不走被测仓储：清理不该依赖被测代码是否正常，
     * 而「成员行指向不存在的会话」这件事本来就不是仓储能造出来的。
     * 这条 SQL 仍然走 ShardingSphere 逻辑表（与生产同一条路径）。
     */
    private void execute(String sql, long... args) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setLong(i + 1, args[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("IT 辅助 SQL 失败: " + sql, e);
        }
    }
}
