package com.tm.im.api.user.view;

import com.tm.im.domain.entity.Actor;

import java.time.ZoneId;

/**
 * 领域实体 → 对外视图的<b>唯一</b>转换点。
 *
 * <p>散着写 {@code new ActorView(...)} 会让「某个接口少填了一个字段」
 * 变成静默的空值（Jackson 不报错），而客户端看到的是「这个人的头像丢了」——
 * 一个会被当成数据问题去查库的现象。
 *
 * <p>时区换算也在这里，只此一处：{@code tm.time.zone} 决定库里
 * {@code DATETIME} 的含义（{@code CoreConfiguration} 里那个 Bean）。
 * 让每个接口自己 {@code atZone} 的话，漏掉的那个接口就会把时间少算 8 小时。
 */
public final class ActorViews {

    private ActorViews() {
    }

    public static ActorView toView(Actor actor, ZoneId databaseZone) {
        return new ActorView(
                actor.getId(),
                actor.getActorType() == null ? 0 : actor.getActorType().code(),
                actor.getHandle(),
                actor.getDisplayName(),
                actor.getAvatarUrl(),
                actor.getBio(),
                actor.getStatus() == null ? 0 : actor.getStatus().code(),
                // createdAt 在库里是 NOT NULL，但手工插入的数据或用例构造的实体可能为 null。
                // 这里允许 null 而不是抛：一个空的时间字段不该让整个「查我是谁」失败。
                actor.getCreatedAt() == null ? null : actor.getCreatedAt().atZone(databaseZone).toInstant());
    }
}
