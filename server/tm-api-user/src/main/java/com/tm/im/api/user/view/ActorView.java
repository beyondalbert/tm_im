package com.tm.im.api.user.view;

/**
 * Actor 的对外表示（03-rest-api.md §2.1 / §2.2）。
 *
 * <p>{@code actorType} 与 {@code status} 是<b>数字</b>而不是字符串：
 * 文档的示例里就是数字（{@code "actor_type": 2}、{@code "status": 1}），
 * 与库里的 TINYINT 编码一致（见 {@code ActorType} / {@code ActorStatus}）。
 * 输出字符串会让「同一个枚举在两处（REST 与长连接帧）表现不同」——
 * 长连接的 {@code AuthResponse.actor_type} 也是数字。
 *
 * <p>{@code createdAt} 是 {@link java.time.Instant} 而不是 {@code LocalDateTime}：
 * 库里存的是不带时区的墙上时间（{@code tm.time.zone} 定义它的含义），
 * 直接序列化 {@code LocalDateTime} 会得到 {@code 2026-01-01T08:00:00}
 * 这样一个<b>没有时区、却被客户端当成本地时间</b>的字符串，
 * 于是「同一时刻在两端相差 8 小时」，而消息收发、排序、未读数全部正常。
 * 用 {@code Instant} 让它必须经过 {@code atZone(zone).toInstant()} 显式换算——
 * 类型系统强迫写出「这个时间属于哪个时区」。
 */
public record ActorView(long actorId, int actorType, String handle, String displayName,
                        String avatarUrl, String bio, int status,
                        java.time.Instant createdAt) {
}
