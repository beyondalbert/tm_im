package com.tm.im.api.user.view;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 库里的墙上时间 → 对外的 {@link Instant}，<b>统一截到毫秒</b>。
 *
 * <p>为什么要截：库里所有时间列都是 {@code DATETIME(3)}（毫秒精度，
 * 见 DESIGN §9.4），而 03-rest-api.md §1.6 把对外的格式钉成了
 * 「ISO 8601 UTC 毫秒」——{@code 2026-01-01T08:00:00.123Z}。
 *
 * <p>不截的话会得到一个只在**某些**接口上出现的格式：
 * 凡是「从库里读出来再返回」的都是毫秒（列本身就是毫秒），
 * 而「刚写进去就返回」的那些（建群、发消息、发起好友请求）带着
 * {@code LocalDateTime.now()} 的纳秒，序列化成
 * {@code 2026-01-01T08:00:00.123456789Z}。
 * 这个差别不会让任何解析失败（客户端按 ISO 解析都能过），
 * 但会让「同一份文档下两种时间格式」，而需要按字符串比对时间的调用方
 * （缓存键、幂等键、前端按秒截断的展示逻辑）会在不同的接口上表现不一致。
 */
final class Timestamps {

    private Timestamps() {
    }

    /** 转 Instant 并截到毫秒；{@code time} 为 null 时返回 null（手工数据可能缺列）。 */
    static Instant millis(LocalDateTime time, ZoneId zone) {
        return time == null ? null : time.atZone(zone).toInstant().truncatedTo(ChronoUnit.MILLIS);
    }
}
