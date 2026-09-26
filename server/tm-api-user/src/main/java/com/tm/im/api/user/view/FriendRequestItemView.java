package com.tm.im.api.user.view;

import java.time.Instant;

/**
 * §3.3 好友请求列表的一项。
 *
 * <p>与 §3.1 的 {@link FriendRequestView} 形状不同（这里 {@code from_actor}/{@code to_actor}
 * 是<b>对象</b>），因为两个接口的用途不同：§3.1 是「我刚发出的那一条」，
 * 客户端手里已经有目标信息；§3.3 是列表，它必须能直接渲染出「谁加了你」
 * ——那需要 handle 与昵称，而客户端手里没有。
 *
 * <p>{@code message} 可能是 {@code null}（附言是可选的）。
 */
public record FriendRequestItemView(long requestId,
                                    ActorRefView fromActor,
                                    ActorRefView toActor,
                                    String message,
                                    int status,
                                    Instant createdAt,
                                    Instant expiresAt) {
}
