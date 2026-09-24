package com.tm.im.domain.policy;

import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.domain.enums.ConvType;
import com.tm.im.domain.enums.FriendshipStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 发消息准入规则的穷举测试。
 *
 * <p>输入维度共 2 × 2 × 4 = 16 种组合，这里<b>一种不漏地全部钉死</b>。
 * 这是把规则抽成纯函数换来的能力：若规则写在 Service 里，
 * 就只能靠连上数据库跑集成测试去覆盖，而外部数据库当前根本连不上。
 */
class MessageSendPolicyTest {

    private static final FriendshipStatus[] ALL_STATUSES = {
            null, FriendshipStatus.PENDING, FriendshipStatus.ACCEPTED, FriendshipStatus.BLOCKED
    };

    @Test
    @DisplayName("单聊：只有「是成员 + 已是好友」才放行，其余全部拒绝")
    void directChatTruthTable() {
        for (boolean isMember : new boolean[]{true, false}) {
            for (FriendshipStatus friendship : ALL_STATUSES) {
                ErrorCode actual = MessageSendPolicy.evaluate(ConvType.DIRECT, isMember, friendship);

                ErrorCode expected;
                if (!isMember) {
                    expected = ErrorCode.NOT_A_MEMBER;
                } else if (friendship == null || friendship == FriendshipStatus.PENDING) {
                    expected = ErrorCode.NOT_FRIENDS;
                } else if (friendship == FriendshipStatus.BLOCKED) {
                    expected = ErrorCode.BLOCKED_BY_PEER;
                } else {
                    expected = null;
                }

                assertThat(actual)
                        .as("单聊 isMember=%s friendship=%s", isMember, friendship)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("群聊：只校验成员身份，好友关系一律不参与判定")
    void groupChatIgnoresFriendship() {
        for (FriendshipStatus friendship : ALL_STATUSES) {
            assertThat(MessageSendPolicy.evaluate(ConvType.GROUP, true, friendship))
                    .as("群内好友状态为 %s 也应放行——逐条校验 N 次查询不可接受", friendship)
                    .isNull();
        }
        assertThat(MessageSendPolicy.evaluate(ConvType.GROUP, false, FriendshipStatus.ACCEPTED))
                .as("不是群成员时，即使是好友也不能发")
                .isEqualTo(ErrorCode.NOT_A_MEMBER);
    }

    @Test
    @DisplayName("非成员优先于好友关系被拒（避免向非成员泄露「你们是不是好友」）")
    void membershipTakesPrecedence() {
        assertThat(MessageSendPolicy.evaluate(ConvType.DIRECT, false, FriendshipStatus.ACCEPTED))
                .isEqualTo(ErrorCode.NOT_A_MEMBER);
        assertThat(MessageSendPolicy.evaluate(ConvType.DIRECT, false, null))
                .as("既非成员又非好友时，返回 40303 而不是 40003")
                .isEqualTo(ErrorCode.NOT_A_MEMBER);
    }

    @Test
    @DisplayName("拉黑与不是好友要区分：前者应停止重试，后者可引导加好友")
    void blockedIsDistinctFromNotFriends() {
        assertThat(MessageSendPolicy.evaluate(ConvType.DIRECT, true, FriendshipStatus.BLOCKED))
                .isEqualTo(ErrorCode.BLOCKED_BY_PEER);
        assertThat(MessageSendPolicy.evaluate(ConvType.DIRECT, true, FriendshipStatus.PENDING))
                .as("申请待通过仍算「不是好友」")
                .isEqualTo(ErrorCode.NOT_FRIENDS);
    }

    @Test
    @DisplayName("check() 抛出的异常码与 evaluate() 一致，且走 HTTP 200 语义")
    void checkThrowsMatchingError() {
        assertThatThrownBy(() -> MessageSendPolicy.check(ConvType.DIRECT, true, null))
                .isInstanceOf(TmException.class)
                .satisfies(e -> {
                    TmException t = (TmException) e;
                    assertThat(t.code()).isEqualTo(40003);
                    assertThat(t.httpStatus()).as("业务失败必须是 HTTP 200").isEqualTo(200);
                    assertThat(t.retryable()).as("重试一万次也不会变成好友").isFalse();
                });

        assertThatCode(() -> MessageSendPolicy.check(ConvType.DIRECT, true, FriendshipStatus.ACCEPTED))
                .doesNotThrowAnyException();
        assertThatCode(() -> MessageSendPolicy.check(ConvType.GROUP, true, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("convType 为 null 必须报错——不能默默按群聊放行")
    void nullConvTypeIsRejected() {
        assertThatThrownBy(() -> MessageSendPolicy.evaluate(null, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("convType");
    }
}
