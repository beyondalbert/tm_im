package com.tm.im.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 密码哈希的边界。
 *
 * <p>测试用<b>低迭代数</b>（{@link #FAST}）跑绝大多数用例：这个类的正确性与迭代数无关，
 * 而默认的 21 万次在 30 个用例上会变成十几秒的纯等待。默认值本身由
 * {@link #defaultIterationsAreActuallyUsedAndStrongEnough()} 单独钉一次。
 */
class PasswordHashesTest {

    /** 迭代数只是为了跑得快，不参与任何断言语义。 */
    private static final int FAST = 1_000;

    @Test
    @DisplayName("同一口令两次哈希结果不同（每次都用新的随机盐）")
    void saltIsRandomPerHash() {
        String a = PasswordHashes.hash("correct horse battery staple", FAST);
        String b = PasswordHashes.hash("correct horse battery staple", FAST);

        assertThat(a).isNotEqualTo(b);
        assertThat(PasswordHashes.verify("correct horse battery staple", a)).isTrue();
        assertThat(PasswordHashes.verify("correct horse battery staple", b)).isTrue();
    }

    @Test
    @DisplayName("编码格式自带参数：算法$迭代数$盐$摘要，且迭代数与传入的一致")
    void formatCarriesItsParameters() {
        String encoded = PasswordHashes.hash("pw-12345678", FAST);

        String[] parts = encoded.split("\\$", -1);
        assertThat(parts).hasSize(4);
        assertThat(parts[0]).isEqualTo(PasswordHashes.ALGORITHM);
        assertThat(parts[1]).isEqualTo(String.valueOf(FAST));
        // base64url 且无填充 —— 有 '=' 的话，把它写进 URL 或 JSON 时就要额外处理
        assertThat(parts[2]).doesNotContain("=").doesNotContain("+").doesNotContain("/");
        assertThat(parts[3]).doesNotContain("=").doesNotContain("+").doesNotContain("/");
        assertThat(PasswordHashes.iterationsOf(encoded)).isEqualTo(FAST);
    }

    @Test
    @DisplayName("口令错误返回 false，而不是抛异常")
    void wrongPasswordReturnsFalse() {
        String encoded = PasswordHashes.hash("right-password", FAST);

        assertThat(PasswordHashes.verify("wrong-password", encoded)).isFalse();
        // 一个字符的差别（大小写与尾部空格）也必须判为不匹配：
        // trim / 忽略大小写会把口令空间砍掉一大截
        assertThat(PasswordHashes.verify("Right-password", encoded)).isFalse();
        assertThat(PasswordHashes.verify("right-password ", encoded)).isFalse();
        assertThat(PasswordHashes.verify("right-passwor", encoded)).isFalse();
    }

    @Test
    @DisplayName("中文与 emoji 口令可往返：编码差异会让「换个 JDK 就登不上」")
    void nonAsciiPasswordsRoundTrip() {
        String password = "口令-🎉-密码";
        String encoded = PasswordHashes.hash(password, FAST);

        assertThat(PasswordHashes.verify(password, encoded)).isTrue();
        assertThat(PasswordHashes.verify("口令-🎉-密码2", encoded)).isFalse();
    }

    @Test
    @DisplayName("默认迭代数被真的用上，且不低于强度下限")
    void defaultIterationsAreActuallyUsedAndStrongEnough() {
        String encoded = PasswordHashes.hash("pw-12345678");

        assertThat(PasswordHashes.iterationsOf(encoded))
                .isEqualTo(PasswordHashes.DEFAULT_ITERATIONS);
        // 20 万次是 2020 年代对 PBKDF2-HMAC-SHA256 的常见下限。写成断言是为了
        // 「为了跑得快把它调小」这个改动必须显式地失败一次，而不是悄悄生效。
        assertThat(PasswordHashes.DEFAULT_ITERATIONS).isGreaterThanOrEqualTo(200_000);
        assertThat(PasswordHashes.SALT_BYTES).isGreaterThanOrEqualTo(16);
    }

    @Test
    @DisplayName("needsRehash 只对旧参数为真：参数升级的判据")
    void needsRehashOnlyForOutdatedParameters() {
        assertThat(PasswordHashes.needsRehash(PasswordHashes.hash("pw", FAST))).isTrue();
        assertThat(PasswordHashes.needsRehash(PasswordHashes.hash("pw"))).isFalse();

        // 比默认更高（未来某次升级后的存量数据）不需要重算：重算只会白花 CPU，
        // 而每次登录都重算一次等于给登录路径加一笔固定开销
        String future = PasswordHashes.hash("pw", PasswordHashes.DEFAULT_ITERATIONS + 1);
        assertThat(PasswordHashes.needsRehash(future)).isFalse();
    }

    @Test
    @DisplayName("旧参数哈希仍能验证：升级迭代数不该把所有人登出")
    void oldParameterHashesStayVerifiable() {
        // 模拟「一年前用 5 万次迭代存的哈希」
        String stored = PasswordHashes.hash("legacy-password", 50_000);
        assertThat(stored).startsWith(PasswordHashes.ALGORITHM + "$50000$");

        assertThat(PasswordHashes.verify("legacy-password", stored)).isTrue();
        assertThat(PasswordHashes.needsRehash(stored)).isTrue();
    }

    @Test
    @DisplayName("坏掉的存储值抛异常，而不是伪装成「口令错误」")
    void malformedStoredValueThrowsInsteadOfReturningFalse() {
        // 这一条是刻意的：若坏值返回 false，一行被截断的哈希会把用户永久锁在
        // 门外，而日志里只有一串「密码错误」——排查方向完全错。
        assertThatThrownBy(() -> PasswordHashes.verify("pw", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("为空");
        assertThatThrownBy(() -> PasswordHashes.verify("pw", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PasswordHashes.verify("pw", "not-a-hash"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4 段");
        // 末尾多一个分隔符：split 若不用 -1，这里会被悄悄当成三段式通过
        assertThatThrownBy(() -> PasswordHashes.verify("pw", PasswordHashes.hash("pw", FAST) + "$"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PasswordHashes.verify("pw", "bcrypt$1000$abcd$efgh"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("算法");
        assertThatThrownBy(() -> PasswordHashes.verify("pw", PasswordHashes.ALGORITHM + "$abc$abcd$efgh"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("迭代数");
        assertThatThrownBy(() -> PasswordHashes.verify("pw", PasswordHashes.ALGORITHM + "$0$abcd$efgh"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非正");
        assertThatThrownBy(() -> PasswordHashes.verify("pw", PasswordHashes.ALGORITHM + "$1000$***$efgh"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base64url");
        assertThatThrownBy(() -> PasswordHashes.verify("pw", PasswordHashes.ALGORITHM + "$1000$$"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("为空");
    }

    @Test
    @DisplayName("空口令与 null 不能产生可落库的哈希")
    void emptyPasswordIsRejected() {
        // 允许它们就会产生一个「任意空口令都能登录」的账号
        assertThatThrownBy(() -> PasswordHashes.hash(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PasswordHashes.hash(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PasswordHashes.verify(null, PasswordHashes.hash("pw", FAST)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PasswordHashes.hash("pw", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("大规模哈希不产生重复（盐确实随机）")
    void noCollisionsAcrossManyHashes() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(PasswordHashes.hash("same-password", FAST));
        }
        assertThat(seen).hasSize(200);
    }
}
