package com.tm.im.api.user.view;

import com.tm.im.domain.entity.Actor;

/**
 * §3.3 里 {@code from_actor} / {@code to_actor} 的形状：只有渲染一行请求需要的字段。
 *
 * <p>不用 {@link ActorView}：那个还带 {@code status}（账号是否停用）与 {@code created_at}，
 * 而这两个字段在「谁加了你」这个位置既没用处又多一层信息暴露。
 * 视图类只装「这一处要用到的东西」，是让「某个接口多返回了一个字段」
 * 变成一次显式的代码改动（而不是顺手复用带来的静默泄漏）。
 */
public record ActorRefView(long actorId, int actorType, String handle, String displayName) {

    public static ActorRefView of(Actor actor) {
        return new ActorRefView(actor.getId(),
                actor.getActorType() == null ? 0 : actor.getActorType().code(),
                actor.getHandle(),
                actor.getDisplayName());
    }
}
