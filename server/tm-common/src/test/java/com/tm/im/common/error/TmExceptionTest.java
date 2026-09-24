package com.tm.im.common.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务异常测试。
 *
 * <p>重点是那条刻意的取舍：4xxxx 不采集堆栈（预期内控制流），
 * 5xxxx 采集（服务端缺陷）。这里把差异<b>断言下来</b>，
 * 免得以后有人觉得「异常怎么能没堆栈」而顺手改回去，
 * 让高频业务路径重新承担无意义的填充开销。
 */
class TmExceptionTest {

    @Test
    @DisplayName("业务异常（4xxxx）不采集堆栈：非好友发消息不是故障，是正常控制流")
    void clientErrorsHaveNoStackTrace() {
        TmException e = TmException.notFriends(1L, 2L);

        assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FRIENDS);
        assertThat(e.code()).isEqualTo(40003);
        assertThat(e.httpStatus()).isEqualTo(200);
        assertThat(e.retryable()).isFalse();
        assertThat(e.detail()).isEqualTo("from=1,to=2");
        assertThat(e.getMessage()).isEqualTo("[40003] not friends (from=1,to=2)");

        assertThat(e.getStackTrace())
                .as("高频业务异常采集堆栈是纯浪费：位置就是抛出点本身，信息量为零")
                .isEmpty();
    }

    @Test
    @DisplayName("服务端异常（5xxxx）照常采集堆栈：这是排查缺陷的唯一线索")
    void serverErrorsKeepStackTrace() {
        TmException e = new TmException(ErrorCode.DATABASE_UNAVAILABLE, "mysql unreachable");

        assertThat(e.httpStatus()).isEqualTo(500);
        assertThat(e.retryable()).isTrue();
        assertThat(e.getStackTrace()).as("服务端异常必须保留堆栈").isNotEmpty();
        assertThat(e.getMessage()).isEqualTo("[50001] database unavailable (mysql unreachable)");
    }

    @Test
    @DisplayName("包装底层异常时始终保留 cause")
    void causeIsPreserved() {
        RuntimeException root = new RuntimeException("connection reset");
        TmException e = new TmException(ErrorCode.STORAGE_UNAVAILABLE, "oss", root);

        assertThat(e.getCause()).isSameAs(root);
        assertThat(e.getStackTrace()).isNotEmpty();
    }

    @Test
    @DisplayName("无 detail 时不生成空括号")
    void messageWithoutDetail() {
        assertThat(new TmException(ErrorCode.NOT_FOUND).getMessage())
                .isEqualTo("[40400] not found");
        assertThat(new TmException(ErrorCode.NOT_FOUND, "  ").getMessage())
                .as("空白 detail 与未提供等价")
                .isEqualTo("[40400] not found");
    }

    @Test
    @DisplayName("service overloaded 走 503 而非 500，与文档 §1 表格一致")
    void overloadedIs503() {
        assertThat(new TmException(ErrorCode.SERVICE_OVERLOADED).httpStatus()).isEqualTo(503);
    }
}
