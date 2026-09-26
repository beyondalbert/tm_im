package com.tm.im.api.user.view;

import com.tm.im.core.conversation.ConversationService;
import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.entity.Conversation;
import com.tm.im.domain.entity.Message;
import com.tm.im.domain.entity.ConversationMember;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话相关实体 → 对外视图的<b>唯一</b>转换点（与 {@link ActorViews} / {@link MessageViews} 同一条思路）。
 *
 * <p>散着写 {@code new ConversationView(...)} 的代价在这里尤其具体：会话视图有 11 个字段，
 * 而其中一个（{@code unreadCount}）是<b>算出来的</b>，另一个（{@code updatedAt}）需要时区换算。
 * 让控制器自己拼的话，漏算未读数不会报错，只会让红点不显示——一个会被当成「前端 bug」的现象。
 */
public final class ConversationViews {

    private ConversationViews() {
    }

    public static PeerView peer(Actor actor) {
        if (actor == null) {
            // 只有「单聊里对方那一行不见了」（数据损坏）时才会走到这里。
            // 返回 null 而不是造一个 handle 为 "<unknown>" 的假人：
            // 客户端对 null 有现成的处理（显示「未知用户」），对假 handle 没有。
            return null;
        }
        return new PeerView(actor.getId(),
                actor.getActorType() == null ? 0 : actor.getActorType().code(),
                actor.getHandle(),
                actor.getDisplayName(),
                actor.getAvatarUrl());
    }

    public static MemberView member(ConversationService.ConversationMemberInfo info, ZoneId databaseZone) {
        Actor actor = info.actor();
        ConversationMember row = info.member();
        return new MemberView(
                actor == null ? nullId(row.getActorId()) : actor.getId(),
                actor == null || actor.getActorType() == null ? 0 : actor.getActorType().code(),
                actor == null ? null : actor.getHandle(),
                actor == null ? null : actor.getDisplayName(),
                actor == null ? null : actor.getAvatarUrl(),
                row.getRole() == null ? 0 : row.getRole().code(),
                instant(row.getJoinedAt(), databaseZone));
    }

    public static ConversationView conversation(ConversationService.ConversationSummary summary,
                                                ZoneId databaseZone) {
        Conversation conv = summary.conversation();
        return new ConversationView(
                conv.getId(),
                conv.getConvType() == null ? 0 : conv.getConvType().code(),
                conv.getTitle(),
                peer(summary.peer()),
                summary.lastSeq(),
                summary.lastReadSeq(),
                summary.unreadCount(),
                summary.lastMessage() == null ? null : MessageViews.toView(summary.lastMessage(), databaseZone),
                summary.muted(),
                summary.memberCount(),
                instant(summary.updatedAt(), databaseZone));
    }

    /**
     * 会话列表分页。
     *
     * <p>方法名不能只叫 {@code page}：它与下面的消息分页在泛型擦除之后是同一个签名
     * （{@code Page<X>} 都擦成 {@code Page}），编译器会直接报「名称冲突」。
     * 这正好是「两种分页本来就该是两个类型」的另一个证据。
     */
    public static ConversationPageView conversationPage(
            ConversationService.Page<ConversationService.ConversationSummary> page, ZoneId databaseZone) {
        List<ConversationView> items = new ArrayList<>(page.items().size());
        for (ConversationService.ConversationSummary summary : page.items()) {
            items.add(conversation(summary, databaseZone));
        }
        return new ConversationPageView(items, page.nextCursor(), page.hasMore());
    }

    public static ConversationDetailView detail(ConversationService.ConversationDetail detail,
                                                ZoneId databaseZone) {
        Conversation conv = detail.conversation();
        List<MemberView> members = new ArrayList<>(detail.members().size());
        for (ConversationService.ConversationMemberInfo info : detail.members()) {
            members.add(member(info, databaseZone));
        }
        ConversationMember me = detail.me().member();
        return new ConversationDetailView(
                conv.getId(),
                conv.getConvType() == null ? 0 : conv.getConvType().code(),
                conv.getTitle(),
                conv.getOwnerActor(),
                detail.memberCount(),
                detail.lastSeq(),
                me.getLastReadSeq() == null ? 0L : me.getLastReadSeq(),
                detail.unreadCount(),
                members,
                instant(conv.getCreatedAt(), databaseZone));
    }

    public static DirectConversationView direct(ConversationService.DirectOutcome outcome,
                                                ZoneId databaseZone) {
        Conversation conv = outcome.conversation();
        return new DirectConversationView(
                conv.getId(),
                conv.getConvType() == null ? 0 : conv.getConvType().code(),
                peer(outcome.peer()),
                outcome.created(),
                outcome.lastSeq());
    }

    public static GroupConversationView group(ConversationService.GroupOutcome outcome,
                                              ZoneId databaseZone) {
        Conversation conv = outcome.conversation();
        return new GroupConversationView(
                conv.getId(),
                conv.getConvType() == null ? 0 : conv.getConvType().code(),
                conv.getTitle(),
                conv.getOwnerActor() == null ? 0L : conv.getOwnerActor(),
                outcome.members().size(),
                instant(conv.getCreatedAt(), databaseZone));
    }

    public static ReadView read(ConversationService.ReadOutcome outcome) {
        return new ReadView(outcome.convId(), outcome.lastReadSeq(), outcome.unreadCount());
    }

    // ---------------------------------------------------------------- §4.9 群成员管理

    public static MemberAddView memberAdd(ConversationService.AddOutcome outcome, ZoneId databaseZone) {
        List<MemberView> added = new ArrayList<>(outcome.added().size());
        for (ConversationService.ConversationMemberInfo info : outcome.added()) {
            added.add(member(info, databaseZone));
        }
        return new MemberAddView(outcome.convId(), added, outcome.alreadyMembers(), outcome.memberCount());
    }

    public static MemberRemovedView memberRemoved(ConversationService.RemoveOutcome outcome) {
        return new MemberRemovedView(outcome.convId(), outcome.actorId(), outcome.memberCount());
    }

    public static MemberRoleView memberRole(ConversationService.RoleOutcome outcome) {
        return new MemberRoleView(outcome.convId(), outcome.actorId(), outcome.role().code(),
                outcome.ownerActor(), outcome.memberCount());
    }

    public static ConversationTitleView title(ConversationService.TitleOutcome outcome) {
        return new ConversationTitleView(outcome.convId(), outcome.title());
    }

    public static MessagePageView messagePage(ConversationService.Page<Message> page, ZoneId databaseZone) {
        return new MessagePageView(MessageViews.toViews(page.items(), databaseZone),
                page.nextCursor(), page.hasMore());
    }

    public static IncrementalView incremental(ConversationService.IncrementalOutcome outcome,
                                              ZoneId databaseZone) {
        return new IncrementalView(MessageViews.toViews(outcome.items(), databaseZone),
                outcome.latestSeq(), outcome.hasMore());
    }

    private static long nullId(Long value) {
        return value == null ? 0L : value;
    }

    private static java.time.Instant instant(LocalDateTime time, ZoneId databaseZone) {
        return Timestamps.millis(time, databaseZone);
    }
}
