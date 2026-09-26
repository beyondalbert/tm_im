package com.tm.im.api.user.view;

import java.time.Instant;
import java.util.List;

/**
 * 会话详情（03-rest-api.md §4.4）。
 *
 * <p>{@code memberCount} 是<b>精确值</b>（COUNT），而 {@code members} 最多返回
 * {@code tm.conversation.max-group-members} 条。两者可能不相等（超大群），
 * 所以它们必须分开给：只给 {@code members} 的话客户端会把「我看到的」当成「全部」，
 * 从而把一个 500 人的群显示成「499 人」里的某一个；只给 {@code memberCount}
 * 又渲染不出成员列表。成员列表的分页在 M4（见 DESIGN §14.1）。
 */
public record ConversationDetailView(long convId,
                                     int convType,
                                     String title,
                                     Long ownerActor,
                                     long memberCount,
                                     long lastSeq,
                                     long myLastReadSeq,
                                     long unreadCount,
                                     List<MemberView> members,
                                     Instant createdAt) {
}
