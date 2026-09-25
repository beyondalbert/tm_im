package com.tm.im.api.user.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceIdTest {

    @Test
    @DisplayName("未上报 → null（不是空串：两者将来要能区分）")
    void missingHeaderIsNull() {
        assertThat(DeviceId.from(null)).isNull();
        assertThat(DeviceId.from("")).isNull();
        assertThat(DeviceId.from("   ")).isNull();
    }

    @Test
    @DisplayName("去掉前后空白：客户端常带上换行或空格")
    void stripsWhitespace() {
        assertThat(DeviceId.from("  web-chrome-131\n")).isEqualTo("web-chrome-131");
    }

    @Test
    @DisplayName("超长截断而不是拒绝：设备标识不该让登录失败")
    void truncatesInsteadOfRejecting() {
        String value = DeviceId.from("x".repeat(500));

        assertThat(value).hasSize(DeviceId.MAX_LENGTH);
        // 截断本身要稳定：同一个入参两次结果相同，否则 Redis 里的值会反复变
        assertThat(DeviceId.from("x".repeat(500))).isEqualTo(value);
    }

    @Test
    @DisplayName("正好到上限不截断")
    void boundaryIsExact() {
        String exact = "y".repeat(DeviceId.MAX_LENGTH);
        assertThat(DeviceId.from(exact)).isEqualTo(exact);
        assertThat(DeviceId.from(exact + "z")).hasSize(DeviceId.MAX_LENGTH);
    }
}
