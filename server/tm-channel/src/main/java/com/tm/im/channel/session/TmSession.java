package com.tm.im.channel.session;

import com.tm.im.core.identity.AuthContext;
import com.tm.im.domain.enums.ActorType;

import java.time.Instant;

/**
 * 一条已鉴权连接的会话快照。
 *
 * <p>挂在 {@code Channel} 的 attribute 上，而不是放在 handler 的成员变量里：
 * handler 是<b>共享</b>的（每个 Channel 复用同一个实例），把连接状态写在
 * handler 字段上会让两个连接互相覆盖 —— 而这个 bug 在单连接测试里完全看不出来。
 */
public record TmSession(
        long actorId,
        String handle,
        ActorType actorType,
        String deviceId,
        String remoteAddress,
        Instant connectedAt) {

    public static TmSession of(AuthContext auth, String remoteAddress, Instant connectedAt) {
        return new TmSession(auth.actorId(), auth.handle(), auth.actorType(),
                auth.deviceId(), remoteAddress, connectedAt);
    }
}
