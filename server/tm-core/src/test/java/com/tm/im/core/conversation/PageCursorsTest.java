package com.tm.im.core.conversation;

import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 游标编解码的规则。
 *
 * <p><b>为什么值得单独一个测试类</b>：游标是分页的唯一状态，而它跨版本、跨接口地活着——
 * 客户端可能把一个三周前拿到的游标发回来，也可能把消息游标贴到会话列表上。
 * 这些情况的错法都一样：服务端<b>不报错</b>，只是返回一个内容不对的列表，
 * 而客户端没有任何办法发现。所以这里的每一条断言都是「坏输入必须变成 40010」。
 *
 * <p>尤其重要的是那条「类型不匹配要报错」：如果游标里只带一个数字，
 * 两种游标互换就会静默生效——{@code seq=7} 被当成「活跃时间 7 毫秒」，
 * 于是会话列表的第一页是一堆最老的会话。
 */
class PageCursorsTest {

    @Test
    @DisplayName("往返：会话游标与消息游标都能原样解回来")
    void roundTrip() {
        String conv = PageCursors.encodeConversation(1_767_225_600_123L, 1001L);
        assertThat(PageCursors.decodeConversation(conv))
                .isEqualTo(new PageCursors.ConversationCursor(1_767_225_600_123L, 1001L));

        String msg = PageCursors.encodeMessage(7L);
        assertThat(PageCursors.decodeMessage(msg)).isEqualTo(new PageCursors.MessageCursor(7L));
    }

    @Test
    @DisplayName("URL 安全：游标里不出现 + / = （客户端放进查询串时不需要再转义）")
    void cursorIsUrlSafe() {
        String conv = PageCursors.encodeConversation(Long.MAX_VALUE / 2, Long.MAX_VALUE / 3);
        assertThat(conv).matches("[A-Za-z0-9_-]+");
        assertThat(PageCursors.encodeMessage(9_223_372_036_854_775_807L)).matches("[A-Za-z0-9_-]+");
    }

    @Test
    @DisplayName("类型必须匹配：把消息游标当会话游标用 → 40010（不能静默当成时间戳）")
    void typeMismatchIsRejected() {
        assertThatThrownBy(() -> PageCursors.decodeConversation(PageCursors.encodeMessage(7L)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_CURSOR);

        assertThatThrownBy(() -> PageCursors.decodeMessage(PageCursors.encodeConversation(1L, 2L)))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_CURSOR);
    }

    @Test
    @DisplayName("坏输入一律 40010：空串、非 base64、base64 里的非 JSON、结构缺字段")
    void malformedCursorsAreRejected() {
        assertThatThrownBy(() -> PageCursors.decodeMessage(""))
                .isInstanceOf(TmException.class);
        assertThatThrownBy(() -> PageCursors.decodeMessage("!!!not-base64!!!"))
                .isInstanceOf(TmException.class);
        assertThatThrownBy(() -> PageCursors.decodeMessage(base64("这不是 JSON")))
                .isInstanceOf(TmException.class);
        // JSON 对但字段缺失：{"v":1,"t":"msg"} 少了 seq
        assertThatThrownBy(() -> PageCursors.decodeMessage(base64("{\"v\":1,\"t\":\"msg\"}")))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_CURSOR);
    }

    @Test
    @DisplayName("版本不认识 → 40010（服务端升级过游标结构时，老游标必须被拒绝而不是被误解）")
    void unknownVersionIsRejected() {
        assertThatThrownBy(() -> PageCursors.decodeMessage(base64("{\"v\":2,\"t\":\"msg\",\"seq\":7}")))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_CURSOR);
        assertThatThrownBy(() -> PageCursors.decodeMessage(base64("{\"t\":\"msg\",\"seq\":7}")))
                .isInstanceOf(TmException.class);
    }

    @Test
    @DisplayName("取值必须合法：seq/cid 必须为正，活跃时间不能为负")
    void outOfRangeValuesAreRejected() {
        assertThatThrownBy(() -> PageCursors.decodeMessage(base64("{\"v\":1,\"t\":\"msg\",\"seq\":0}")))
                .as("seq 从 1 开始（取号即消耗），0 不是有效游标")
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_CURSOR);

        assertThatThrownBy(() -> PageCursors.decodeConversation(
                base64("{\"v\":1,\"t\":\"conv\",\"at\":0,\"cid\":0}")))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_CURSOR);

        assertThatThrownBy(() -> PageCursors.decodeConversation(
                base64("{\"v\":1,\"t\":\"conv\",\"at\":-5,\"cid\":9}")))
                .isInstanceOf(TmException.class);
    }

    @Test
    @DisplayName("补了 '=' 的旧版 base64 也要能解（客户端可能把它当普通字符串处理过）")
    void paddedBase64StillDecodes() {
        String padded = Base64.getUrlEncoder()
                .encodeToString("{\"v\":1,\"t\":\"msg\",\"seq\":42}".getBytes(StandardCharsets.UTF_8));
        assertThat(padded).endsWith("=");
        assertThat(PageCursors.decodeMessage(padded).seq()).isEqualTo(42L);
    }

    private static String base64(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
