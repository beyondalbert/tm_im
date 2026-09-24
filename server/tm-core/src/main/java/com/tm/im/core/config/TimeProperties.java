package com.tm.im.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;

/**
 * 时区口径。
 *
 * <p><b>为什么必须有这样一个显式配置项</b>：库里的 {@code DATETIME(3)} 列
 * 映射到 Java 的 {@code LocalDateTime} —— 它<b>不含时区</b>，只是「墙上的时间」。
 * 把这样的值转成 Unix 毫秒（对外协议里的 {@code created_at_ms}）必须指定
 * 「这些墙上时间属于哪个时区」，否则只能猜。猜错的表现是时间整体偏移几小时：
 * 消息能正常收发、排序也正确（排序用的是 seq），只有显示的时间是错的 ——
 * 属于「看着没事、实际上一直错着」的那类问题。
 *
 * <p><b>它必须与 {@code deploy/conf/sharding.yaml} 里 jdbcUrl 的
 * {@code serverTimezone} 参数保持一致</b>，因为那个参数决定了 JDBC 驱动读写
 * {@code DATETIME} 时的口径。两者不一致时，本配置在启动日志里会打印实际取值，
 * 便于比对（见 {@code CoreConfiguration}）。
 *
 * <p>长期方向是「全链路 UTC」（存 UTC、传 UTC、只在展示层转本地），
 * 那属于需要一次数据迁移的独立改造；在此之前，把口径显式化是成本最低且
 * 不会出错的做法。
 */
@ConfigurationProperties(prefix = "tm.time")
public class TimeProperties {

    /** 库里 {@code DATETIME} 所表示的时区。 */
    private ZoneId zone = ZoneId.of("Asia/Shanghai");

    public ZoneId getZone() {
        return zone;
    }

    public void setZone(ZoneId zone) {
        this.zone = zone;
    }
}
