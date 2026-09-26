package com.tm.im.core.conversation;

import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.core.message.MessageService;
import com.tm.im.domain.entity.Conversation;
import com.tm.im.domain.enums.ConvType;
import com.tm.im.domain.enums.MemberRole;
import com.tm.im.domain.enums.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 群成员管理的规则：加人 / 踢人 / 退群 / 改群名 / 改角色（03-rest-api.md §4.9）。
 *
 * <p>与 {@link ConversationServiceTest} 分开写，因为它们钉的是两类东西：那边是「读写语义」
 * （分页、未读数、幂等），这边全是<b>权限与状态机</b>——谁能动谁、群主怎么换、
 * 「群里恰有一个 OWNER」这条不变式怎么维持。
 *
 * <p>这一批规则改起来的代价特别高（客户端会按旧行为实现按钮），所以每条取舍都配了一个用例：
 * ADMIN 能不能改角色（不能）、两个 ADMIN 能不能互踢（不能）、群主能不能直接退群（不能）、
 * 转让之后旧群主是什么角色（ADMIN）、重复加人算不算错（不算）。
 */
class ConversationMemberServiceTest {

    private static final long ALICE = 1001L;
    private static final long BOB = 1002L;
    private static final long CAROL = 1003L;
    private static final long DAVE = 1004L;
    private static final long EVE = 1005L;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 8, 0);

    private InMemoryConversations conversations;
    private InMemoryMessages messages;
    private InMemoryActors actors;
    private RecordingMessageCommands commands;
    private ConversationProperties properties;
    private ConversationService service;

    private long nextConvId = 900_000L;

    @BeforeEach
    void setUp() {
        conversations = new InMemoryConversations();
        messages = new InMemoryMessages();
        actors = new InMemoryActors();
        commands = new RecordingMessageCommands(conversations);
        properties = new ConversationProperties();
        actors.put(ALICE, "alice");
        actors.put(BOB, "bob");
        actors.put(CAROL, "carol");
        actors.put(DAVE, "dave");
        actors.put(EVE, "eve");
        service = new ConversationService(conversations, messages, actors,
                new com.tm.im.core.identity.ActorLookup(actors),
                commands, new SequentialIds(), properties, ZONE);
    }

    // ================================================================ 加人

    @Test
    @DisplayName("加人：OWNER 与 ADMIN 都能加，新成员的初始角色是 MEMBER")
    void ownerAndAdminCanAddMembers() {
        long byOwner = group(ALICE, BOB);
        ConversationService.AddOutcome added = service.addMembers(ALICE, byOwner, List.of("@carol"));

        assertThat(added.added()).extracting(info -> info.member().getActorId()).containsExactly(CAROL);
        assertThat(added.added().get(0).member().getRole()).isEqualTo(MemberRole.MEMBER);
        assertThat(added.added().get(0).actor().getHandle())
                .as("回的是完整成员视图：客户端只有发出去的 handle，而后续踢人/改角色要的是 actor_id")
                .isEqualTo("carol");
        assertThat(added.alreadyMembers()).isEmpty();
        assertThat(added.memberCount()).isEqualTo(3L);
        assertThat(conversations.isMember(byOwner, CAROL)).isTrue();

        // 同一个动作由 ADMIN 做也要成立：ADMIN 的存在意义就是替群主做这些事
        long byAdmin = group(ALICE, BOB);
        setRole(byAdmin, BOB, MemberRole.ADMIN);
        assertThat(service.addMembers(BOB, byAdmin, List.of("@carol")).memberCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("加人：已经在群里的从 already_members 里回，不算错、也不重复写")
    void addingExistingMemberIsNotAnError() {
        long convId = group(ALICE, BOB);

        ConversationService.AddOutcome outcome =
                service.addMembers(ALICE, convId, List.of("@bob", "@carol", "@dave"));

        assertThat(outcome.added()).extracting(info -> info.member().getActorId())
                .as("新来的两个照加")
                .containsExactly(CAROL, DAVE);
        assertThat(outcome.alreadyMembers())
                .as("已是成员的那个照实回给客户端，但不能让其余两个也加不进去")
                .containsExactly(BOB);
        assertThat(conversations.rows(convId)).as("重复的那个人不该被写第二行").hasSize(4);
        assertThat(outcome.memberCount()).isEqualTo(4L);

        // 同一个人在同一次请求里写两遍：去重后只加一次
        long fresh = group(ALICE, BOB);
        assertThat(service.addMembers(ALICE, fresh, List.of("@carol", "@carol")).added()).hasSize(1);
        assertThat(conversations.rows(fresh)).hasSize(3);
    }

    @Test
    @DisplayName("加人：普通成员回 40305、外人回 40303、会话不存在回 40402、单聊回 40302")
    void addingRequiresPrivilegeAndGroup() {
        long convId = group(ALICE, BOB, CAROL);
        assertThat(errorOf(() -> service.addMembers(CAROL, convId, List.of("@dave"))))
                .isEqualTo(ErrorCode.NO_PRIVILEGE);
        assertThat(errorOf(() -> service.addMembers(DAVE, convId, List.of("@carol"))))
                .isEqualTo(ErrorCode.NOT_A_MEMBER);
        assertThat(errorOf(() -> service.addMembers(ALICE, 999_999L, List.of("@carol"))))
                .isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND);

        long direct = directConversation(ALICE, BOB);
        assertThat(errorOf(() -> service.addMembers(ALICE, direct, List.of("@carol"))))
                .as("单聊没有「群内权限」这个概念，所以是「这个资源不支持这个操作」而不是 40305")
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("加人：空 members 回 40001，查不到的人回 40401 且一行都不写")
    void addingValidatesBeforeWriting() {
        long convId = group(ALICE, BOB);

        assertThat(errorOf(() -> service.addMembers(ALICE, convId, List.of())))
                .isEqualTo(ErrorCode.MISSING_PARAMETER);
        assertThat(errorOf(() -> service.addMembers(ALICE, convId, List.of("@carol", "@nobody"))))
                .isEqualTo(ErrorCode.ACTOR_NOT_FOUND);
        assertThat(conversations.rows(convId))
                .as("一批人里有查不到的，整批都不写——否则客户端重试会得到「加了一半」的群")
                .hasSize(2);
    }

    @Test
    @DisplayName("加人：超上限回 40906，但「已在群里的」不占名额")
    void addingStopsAtTheLimitWithoutCountingExistingMembers() {
        properties.setMaxGroupMembers(3);
        long convId = group(ALICE, BOB);

        ConversationService.AddOutcome outcome =
                service.addMembers(ALICE, convId, List.of("@bob", "@carol"));
        assertThat(outcome.added()).extracting(info -> info.member().getActorId()).containsExactly(CAROL);
        assertThat(outcome.memberCount()).as("2 + 1 = 3，正好到上限").isEqualTo(3L);

        assertThat(errorOf(() -> service.addMembers(ALICE, convId, List.of("@dave"))))
                .isEqualTo(ErrorCode.GROUP_MEMBER_LIMIT);
        assertThat(conversations.isMember(convId, DAVE)).isFalse();

        // 一次要加的人比上限还多：不解析 handle 就先拒（省掉 N 次查库）。
        // 注意这里靠的是「个数上界」而不是「当前人数 + 个数」——后面那个会误拒
        // 「一个新人 + 一个已在群里的人」，而那是客户端列表过期，不是它要加太多人。
        assertThat(errorOf(() -> service.addMembers(ALICE, convId,
                List.of("@dave", "@eve", "@carol", "@nobody"))))
                .isEqualTo(ErrorCode.GROUP_MEMBER_LIMIT);
    }

    @Test
    @DisplayName("加人：每个新成员一条 SYSTEM 消息（fromClient=false，带 handle 供客户端渲染）")
    void addingAnnouncesEachMember() {
        long convId = group(ALICE, BOB);
        service.addMembers(ALICE, convId, List.of("@carol", "@dave"));

        assertThat(commands.sent()).hasSize(2);
        MessageService.SendCommand cmd = commands.sent().get(0);
        assertThat(cmd.msgType()).isEqualTo(MessageType.SYSTEM);
        assertThat(cmd.senderId()).as("发起人是邀请者，不是被邀请的人").isEqualTo(ALICE);
        assertThat(cmd.clientMsgId()).as("服务端消息不来自会重试的客户端，不需要幂等键").isNull();
        assertThat(cmd.fromClient()).as("服务端自己产生的消息").isFalse();
        assertThat(cmd.contentJson())
                .contains("\"action\":\"member_joined\"")
                .contains("\"actor_id\":" + CAROL)
                .contains("\"handle\":\"carol\"");
        assertThat(commands.sent().get(1).contentJson()).contains("\"actor_id\":" + DAVE);
    }

    // ================================================================ 踢人

    @Test
    @DisplayName("踢人：群主能踢 ADMIN 和 MEMBER，ADMIN 只能踢 MEMBER，谁都不能踢群主")
    void kickingFollowsTheRoleOrder() {
        long convId = group(ALICE, BOB, CAROL, DAVE);
        setRole(convId, BOB, MemberRole.ADMIN);
        setRole(convId, CAROL, MemberRole.ADMIN);

        assertThat(service.removeMember(ALICE, convId, BOB).memberCount())
                .as("群主踢 ADMIN：允许").isEqualTo(3L);
        assertThat(conversations.isMember(convId, BOB)).isFalse();

        assertThat(service.removeMember(CAROL, convId, DAVE).actorId())
                .as("ADMIN 踢 MEMBER：允许").isEqualTo(DAVE);

        assertThat(errorOf(() -> service.removeMember(CAROL, convId, ALICE)))
                .as("群主不能被移除：没有比 OWNER(1) 更小的角色码，这条规则不需要单独写")
                .isEqualTo(ErrorCode.NO_PRIVILEGE);
        assertThat(errorOf(() -> service.removeMember(ALICE, convId, ALICE)))
                .as("踢自己该走退群那个接口")
                .isEqualTo(ErrorCode.SELF_OPERATION);
        assertThat(conversations.countMembers(convId)).isEqualTo(2L);
    }

    @Test
    @DisplayName("踢人：两个 ADMIN 互相踢不动（同一个判断式的推论）")
    void adminsCannotKickEachOther() {
        long convId = group(ALICE, BOB, CAROL);
        setRole(convId, BOB, MemberRole.ADMIN);
        setRole(convId, CAROL, MemberRole.ADMIN);

        assertThat(errorOf(() -> service.removeMember(BOB, convId, CAROL)))
                .as("若允许，两人可以互相降权/踢出，任何一个顺序都无法收敛")
                .isEqualTo(ErrorCode.NO_PRIVILEGE);
        assertThat(errorOf(() -> service.removeMember(CAROL, convId, BOB)))
                .isEqualTo(ErrorCode.NO_PRIVILEGE);
    }

    @Test
    @DisplayName("踢人：目标不在群里回 40908（不是静默成功——拼错一个 id 不该看起来像踢成功了）")
    void kickingANonMemberIs40908() {
        long convId = group(ALICE, BOB);

        assertThat(errorOf(() -> service.removeMember(ALICE, convId, EVE)))
                .isEqualTo(ErrorCode.TARGET_NOT_MEMBER);
        assertThat(errorOf(() -> service.removeMember(BOB, convId, EVE)))
                .as("先判目标再判权限：40908 与 40305 的客户端动作不同（刷新列表 vs 什么都不做），"
                        + "而「目标不在」是更具体的那一个")
                .isEqualTo(ErrorCode.TARGET_NOT_MEMBER);
        assertThat(errorOf(() -> service.removeMember(EVE, convId, BOB)))
                .as("外人连自己是成员这一关都过不了")
                .isEqualTo(ErrorCode.NOT_A_MEMBER);
    }

    @Test
    @DisplayName("踢人：写 member_removed 的系统消息，且成员行真的没了")
    void kickingAnnouncesAndRemoves() {
        long convId = group(ALICE, BOB, CAROL);
        service.removeMember(ALICE, convId, BOB);

        assertThat(commands.sent()).hasSize(1);
        assertThat(commands.sent().get(0).senderId()).isEqualTo(ALICE);
        assertThat(commands.sent().get(0).contentJson())
                .contains("\"action\":\"member_removed\"")
                .contains("\"actor_id\":" + BOB)
                .contains("\"handle\":\"bob\"");
        assertThat(conversations.isMember(convId, BOB)).isFalse();
        assertThat(conversations.countMembers(convId)).isEqualTo(2L);
    }

    // ================================================================ 退群

    @Test
    @DisplayName("退群：成员能退；群主回 40306 且什么都不改")
    void leavingIsBlockedForTheOwner() {
        long convId = group(ALICE, BOB, CAROL);
        assertThat(service.leaveGroup(CAROL, convId).memberCount()).isEqualTo(2L);
        assertThat(conversations.isMember(convId, CAROL)).isFalse();

        assertThat(errorOf(() -> service.leaveGroup(ALICE, convId)))
                .as("群主退群会让群没有主，而「恰有一个 OWNER」是后面所有权限判断的前提")
                .isEqualTo(ErrorCode.OWNER_CANNOT_LEAVE);
        assertThat(conversations.isMember(convId, ALICE)).isTrue();
        assertThat(commands.sent()).as("被拒的请求不该留下通知").hasSize(1);
    }

    @Test
    @DisplayName("退群：通知先写、成员行后删（写通知的前提是作者还是成员）")
    void leavingAnnouncesBeforeRemovingTheRow() {
        long convId = group(ALICE, BOB, CAROL);
        service.leaveGroup(CAROL, convId);

        assertThat(commands.sent()).hasSize(1);
        assertThat(commands.sent().get(0).senderId()).isEqualTo(CAROL);
        assertThat(commands.sent().get(0).contentJson())
                .contains("\"action\":\"member_left\"")
                .contains("\"handle\":\"carol\"");
        // 顺序由替身钉住：RecordingMessageCommands 与真实 MessageService 一样要求
        // 「SYSTEM 消息的作者当时是成员」。若改成先删后写，这次 send 会被拒，
        // 而服务端只记 ERROR、不让请求失败——于是这条断言就会以「一条通知都没有」的形式失败。
        assertThat(conversations.isMember(convId, CAROL)).isFalse();
    }

    // ================================================================ 改角色 / 转让群主

    @Test
    @DisplayName("改角色：只有群主能做；同值重复上报是幂等的（不写库、不发系统消息）")
    void onlyTheOwnerCanChangeRoles() {
        long convId = group(ALICE, BOB, CAROL);
        setRole(convId, BOB, MemberRole.ADMIN);

        assertThat(errorOf(() -> service.changeRole(BOB, convId, CAROL, 2)))
                .as("ADMIN 不能给别人改角色：否则「群主指定的管理员」会被另一个管理员撤掉")
                .isEqualTo(ErrorCode.NO_PRIVILEGE);
        assertThat(errorOf(() -> service.changeRole(CAROL, convId, BOB, 2)))
                .isEqualTo(ErrorCode.NO_PRIVILEGE);

        ConversationService.RoleOutcome promoted = service.changeRole(ALICE, convId, CAROL, 2);
        assertThat(promoted.role()).isEqualTo(MemberRole.ADMIN);
        assertThat(promoted.ownerActor()).as("角色变了，群主没变").isEqualTo(ALICE);
        assertThat(conversations.member(convId, CAROL).getRole()).isEqualTo(MemberRole.ADMIN);
        assertThat(commands.sent()).hasSize(1);
        assertThat(commands.sent().get(0).contentJson())
                .contains("\"action\":\"member_role_changed\"")
                .contains("\"role\":2");

        int before = commands.sent().size();
        service.changeRole(ALICE, convId, CAROL, 2);
        assertThat(commands.sent())
                .as("已经是 ADMIN 了，再设一次不该产生第二条系统消息")
                .hasSize(before);
    }

    @Test
    @DisplayName("改角色：非法 role 回 40002、改自己回 40904、目标不在群里回 40908")
    void changingRoleValidatesInput() {
        long convId = group(ALICE, BOB);

        assertThat(errorOf(() -> service.changeRole(ALICE, convId, BOB, 0)))
                .as("role 只有 1/2/3：0 与 4 是取值非法，与「没传」的 40001 不是一回事")
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
        assertThat(errorOf(() -> service.changeRole(ALICE, convId, BOB, 4)))
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
        assertThat(errorOf(() -> service.changeRole(ALICE, convId, ALICE, 3)))
                .as("改自己的角色要么让群没有主、要么是给自己降权，两者都没有定义")
                .isEqualTo(ErrorCode.SELF_OPERATION);
        assertThat(errorOf(() -> service.changeRole(ALICE, convId, EVE, 2)))
                .isEqualTo(ErrorCode.TARGET_NOT_MEMBER);
        assertThat(conversations.member(convId, BOB).getRole()).as("失败的请求什么都没改")
                .isEqualTo(MemberRole.MEMBER);
    }

    @Test
    @DisplayName("转让群主：role=1 是原子动作——新群主升 OWNER、旧群主降 ADMIN、owner_actor 重指")
    void transferringOwnershipKeepsExactlyOneOwner() {
        long convId = group(ALICE, BOB, CAROL);

        ConversationService.RoleOutcome outcome = service.changeRole(ALICE, convId, BOB, 1);

        assertThat(outcome.actorId()).isEqualTo(BOB);
        assertThat(outcome.role()).isEqualTo(MemberRole.OWNER);
        assertThat(outcome.ownerActor()).as("响应要让调用者知道「我现在不是群主了」").isEqualTo(BOB);
        assertThat(conversations.member(convId, BOB).getRole()).isEqualTo(MemberRole.OWNER);
        assertThat(conversations.member(convId, ALICE).getRole())
                .as("旧群主降为 ADMIN 而不是 MEMBER：转让交出去的只是身份，不是一次附加的降权")
                .isEqualTo(MemberRole.ADMIN);
        assertThat(conversations.findById(convId).orElseThrow().getOwnerActor()).isEqualTo(BOB);
        assertThat(commands.sent().get(0).contentJson())
                .contains("\"action\":\"owner_transferred\"")
                .contains("\"actor_id\":" + BOB);

        // 转让之后：新群主能改角色，旧群主不能；旧群主可以退群了（40306 只针对群主）
        assertThat(service.changeRole(BOB, convId, CAROL, 2).role()).isEqualTo(MemberRole.ADMIN);
        assertThat(errorOf(() -> service.changeRole(ALICE, convId, CAROL, 3)))
                .isEqualTo(ErrorCode.NO_PRIVILEGE);
        assertThat(service.leaveGroup(ALICE, convId).actorId()).isEqualTo(ALICE);
    }

    @Test
    @DisplayName("转让群主：写入时才发现自己不是群主 → 40305，而不是把新群主又降成 ADMIN")
    void losingTheRaceToTransferIsAPrivilegeError() {
        long convId = group(ALICE, BOB, CAROL);
        conversations.failNextTransferOwnership = true;   // 模拟并发：检查通过之后别人先转走了

        assertThat(errorOf(() -> service.changeRole(ALICE, convId, CAROL, 1)))
                .isEqualTo(ErrorCode.NO_PRIVILEGE);
        assertThat(conversations.member(convId, CAROL).getRole())
                .as("失败必须是「什么都没发生」").isEqualTo(MemberRole.MEMBER);
        assertThat(conversations.member(convId, ALICE).getRole()).isEqualTo(MemberRole.OWNER);
        assertThat(conversations.findById(convId).orElseThrow().getOwnerActor()).isEqualTo(ALICE);
        assertThat(commands.sent()).as("没转成就没有通知").isEmpty();
    }

    // ================================================================ 改群名

    @Test
    @DisplayName("改群名：OWNER/ADMIN 可以，MEMBER 回 40305；空回 40001、超长回 40002（不截断）")
    void renamingChecksPrivilegeAndLength() {
        long convId = group(ALICE, BOB, CAROL);
        setRole(convId, BOB, MemberRole.ADMIN);

        assertThat(service.renameGroup(BOB, convId, "  新群名  ").title())
                .as("两端空白去掉：否则客户端回显的群名与它自己发的多两个空格")
                .isEqualTo("新群名");
        assertThat(conversations.findById(convId).orElseThrow().getTitle()).isEqualTo("新群名");
        assertThat(commands.sent().get(0).contentJson())
                .contains("\"action\":\"title_changed\"")
                .contains("\"title\":\"新群名\"");

        assertThat(errorOf(() -> service.renameGroup(CAROL, convId, "别人的群")))
                .isEqualTo(ErrorCode.NO_PRIVILEGE);
        assertThat(errorOf(() -> service.renameGroup(ALICE, convId, "   ")))
                .isEqualTo(ErrorCode.MISSING_PARAMETER);
        assertThat(errorOf(() -> service.renameGroup(ALICE, convId, "x".repeat(129))))
                .as("截断会让客户端回显的群名与库里不一致，而那种不一致最难被报成 bug")
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
        assertThat(conversations.findById(convId).orElseThrow().getTitle())
                .as("空名与超长都不该改到库里")
                .isEqualTo("新群名");
    }

    @Test
    @DisplayName("改群名：同名重复上报是幂等的（不写库、不发系统消息）")
    void renamingToTheSameTitleIsANoop() {
        long convId = group(ALICE, BOB);

        assertThat(service.renameGroup(ALICE, convId, "G" + convId).title()).isEqualTo("G" + convId);
        assertThat(commands.sent()).isEmpty();
    }

    @Test
    @DisplayName("§4.9 的五个接口对单聊一律不可用（单聊没有群内权限，也没有群名）")
    void everyMemberOperationRejectsDirectConversations() {
        long direct = directConversation(ALICE, BOB);

        assertThat(errorOf(() -> service.addMembers(ALICE, direct, List.of("@carol"))))
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(errorOf(() -> service.removeMember(ALICE, direct, BOB)))
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(errorOf(() -> service.leaveGroup(ALICE, direct)))
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(errorOf(() -> service.changeRole(ALICE, direct, BOB, 2)))
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(errorOf(() -> service.renameGroup(ALICE, direct, "改个名")))
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
    }

    // ================================================================ 辅助

    /** 造一个群：{@code ownerId} 是 OWNER，其余都是 MEMBER。 */
    private long group(long ownerId, long... memberIds) {
        long convId = nextConvId++;
        Conversation conversation = new Conversation();
        conversation.setId(convId);
        conversation.setConvType(ConvType.GROUP);
        conversation.setTitle("G" + convId);
        conversation.setOwnerActor(ownerId);
        conversation.setCreatedAt(T0);
        conversations.put(conversation);
        conversations.putMember(convId, ownerId, MemberRole.OWNER, 0L);
        for (long memberId : memberIds) {
            conversations.putMember(convId, memberId, MemberRole.MEMBER, 0L);
        }
        return convId;
    }

    private long directConversation(long a, long b) {
        long convId = nextConvId++;
        Conversation conversation = new Conversation();
        conversation.setId(convId);
        conversation.setConvType(ConvType.DIRECT);
        conversation.setPairKey(a + "_" + b);
        conversation.setCreatedAt(T0);
        conversations.put(conversation);
        conversations.putMember(convId, a, MemberRole.MEMBER, 0L);
        conversations.putMember(convId, b, MemberRole.MEMBER, 0L);
        return convId;
    }

    private void setRole(long convId, long actorId, MemberRole role) {
        conversations.member(convId, actorId).setRole(role);
    }

    /** 只跑一次动作并取出它抛的错误码（跑两次会把「失败时什么都没改」这类断言弄脏）。 */
    private static ErrorCode errorOf(Runnable action) {
        Throwable thrown = catchThrowable(action::run);
        assertThat(thrown).as("期望抛 TmException，实际 %s", thrown).isInstanceOf(TmException.class);
        return ((TmException) thrown).errorCode();
    }
}
