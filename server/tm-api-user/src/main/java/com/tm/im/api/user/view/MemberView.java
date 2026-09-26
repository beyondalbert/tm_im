package com.tm.im.api.user.view;

import java.time.Instant;

/**
 * 会话成员（03-rest-api.md §4.4 的 {@code members[]}）。
 *
 * <p>{@code role} 是数字（{@code 1=OWNER 2=ADMIN 3=MEMBER}）——与库编码、与长连接一致；
 * 只有「客户端会发回来的枚举」（{@code msg_type}）才用名字，理由见 {@link MessageView}。
 */
public record MemberView(long actorId,
                         int actorType,
                         String handle,
                         String displayName,
                         String avatarUrl,
                         int role,
                         Instant joinedAt) {
}
