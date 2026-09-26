package com.tm.im.api.user.view;

import java.time.Instant;

/**
 * §3.4 好友列表的一项。
 *
 * <p>{@code friendsSince} 是「关系变成 ACCEPTED 的时刻」（库里的 {@code updated_at}），
 * 不是账号创建时间、也不是请求发起时间——它是客户端排序与文案（「3 天前成为好友」）的依据。
 */
public record FriendView(long actorId,
                        int actorType,
                        String handle,
                        String displayName,
                        String avatarUrl,
                        Instant friendsSince) {
}
