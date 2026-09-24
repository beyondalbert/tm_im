// 源: deploy/sql/01-schema.sql  sha256[:16]=7baf623506344ffd
package com.tm.im.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 落库码值来源：friendship.status
 *
 * <p>由 <code>tools/gen_entities.py</code> 生成，请勿手工编辑。
 * 改取值请改生成器中的 ENUM_MAP 后重跑。
 */
public enum FriendshipStatus implements CodedEnum {

    PENDING(1),
    ACCEPTED(2),
    BLOCKED(3);

    /** 落库值。{@code @EnumValue} 告诉 MyBatis-Plus 用这个字段而不是 name()/ordinal()。 */
    @EnumValue
    private final int code;

    FriendshipStatus(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }

    /** 反查；未知码值返回 null，用于解析外部输入。 */
    public static FriendshipStatus of(int code) {
        for (FriendshipStatus v : values()) {
            if (v.code == code) {
                return v;
            }
        }
        return null;
    }

    /**
     * 反查；未知码值直接抛异常。用于<b>读数据库</b>。
     *
     * <p>库里出现 1, 2, 3 之外的值，说明数据已损坏或有人绕过应用直接改库。
     * 此时静默返回 null 会把错误推迟到更远的地方才爆发，还不如就地炸掉。
     */
    public static FriendshipStatus require(int code) {
        FriendshipStatus v = of(code);
        if (v == null) {
            throw new IllegalArgumentException(
                    "非法的 FriendshipStatus 码值: " + code + "（合法值: 1, 2, 3）");
        }
        return v;
    }
}
