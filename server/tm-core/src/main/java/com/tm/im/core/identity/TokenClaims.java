package com.tm.im.core.identity;

import com.tm.im.domain.enums.ActorType;

import java.time.Instant;

/**
 * 从 JWT 中解析出来、且<b>已通过验签</b>的声明。
 *
 * <p>它与 {@link AuthContext} 的区别很重要：本 record 反映的是「token 里写了什么」
 * （签发时的快照），{@link AuthContext} 反映的是「账号现在是什么状态」
 * （鉴权时从库里读的）。权限、封禁状态这类会变的信息只能以后者为准。
 */
public record TokenClaims(
        long actorId,
        String handle,
        ActorType actorType,
        Instant issuedAt,
        Instant expiresAt) {
}
