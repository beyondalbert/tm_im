package com.tm.im.api.user.view;

import java.time.Instant;

/**
 * §3.1 发好友请求的响应。
 *
 * <p>字段与文档示例逐字一致（5 个）。刻意不在这里加 {@code message}/{@code created_at}：
 * 客户端在这一刻手里已经有自己发出去的附言，而它需要的请求详情来自 §3.3 的列表。
 *
 * <p>没有单独的「请求详情」接口：请求只对当事人可见，而当事人要看它的地方
 * 就是那个列表——多一个 `GET /v1/friends/requests/{id}` 只会多一个要鉴权的入口。
 */
public record FriendRequestView(long requestId,
                                long fromActor,
                                long toActor,
                                int status,
                                Instant expiresAt) {
}
