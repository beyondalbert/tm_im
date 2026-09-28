package com.tm.im.api.admin.view;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 库里的墙上时间 → 对外的 {@link Instant}，统一截到毫秒。
 *
 * <p>与用户端的同名类逐字一致，<b>刻意不复用</b>：两者住在不同的 JAR 里，
 * 而共用的代价是 tm-api-admin 必须依赖 tm-api-user——那会把用户端的全部接口
 * 拉进管理后台的进程（见 tm-api-admin/pom.xml 的注释）。
 * 这个类只有 4 行，而依赖方向是单向不可逆的决定。
 */
final class Timestamps {

    private Timestamps() {
    }

    static Instant millis(LocalDateTime time, ZoneId zone) {
        return time == null ? null : time.atZone(zone).toInstant().truncatedTo(ChronoUnit.MILLIS);
    }
}
