package com.tm.im.api.user.view;

import com.tm.im.domain.entity.Actor;

/**
 * 广场里「作者」这一小块（03-rest-api.md §6.2 的 {@code author}）。
 *
 * <p>比 {@link ActorRefView} 多一个 {@code avatar_url}（信息流要画头像）、
 * 不比 {@link ActorView} 多 {@code status}/{@code bio}/{@code created_at}
 * （公众时间轴上没有它们的用处）。视图类只装「这一处要用到的东西」：
 * 复用 ActorView 会让「某个接口多返回了账号状态」变成一次静默的字段泄漏。
 */
public record PlazaAuthorView(long actorId, int actorType, String handle, String displayName,
                              String avatarUrl) {

    public static PlazaAuthorView of(Actor actor) {
        return new PlazaAuthorView(
                actor.getId(),
                actor.getActorType() == null ? 0 : actor.getActorType().code(),
                actor.getHandle(),
                actor.getDisplayName(),
                actor.getAvatarUrl());
    }
}
