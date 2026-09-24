package com.tm.im.core.identity;

import com.tm.im.domain.enums.ActorType;

/**
 * 鉴权成功后的身份上下文 —— 长连接与 REST 共用同一个结构。
 *
 * <p>这是「同构协议」在代码里的落点：无论请求从 Tomcat 还是 Netty 进来，
 * 无论凭证是 JWT 还是 api_key，下游拿到的都是这个对象，
 * 因此下游<b>无法</b>写出「人走这条路、Agent 走那条路」的分支。
 *
 * @param actorId    参与者 ID（Snowflake）
 * @param handle     唯一 handle（含 {@code @}）
 * @param actorType  人 / Agent；<b>仅用于展示元数据与投递适配</b>，不做权限分支
 * @param kind       本次凭证的种类，用于日志与配额（Agent 配额不同于人类）
 * @param deviceId   客户端自报的设备标识，可能为空；用于「同账号多端顶号」判定
 */
public record AuthContext(
        long actorId,
        String handle,
        ActorType actorType,
        CredentialKind kind,
        String deviceId) {

    public boolean isAgent() {
        return actorType == ActorType.AGENT;
    }

    /**
     * 日志友好表示：<b>绝不含凭证</b>。
     *
     * <p>records 的默认 {@code toString()} 只打印字段，本身不带 token；
     * 这里显式写出仍是为了将来有人加字段（如 rawToken）时，
     * 会先看到这段注释并意识到日志里不能出现它。
     */
    @Override
    public String toString() {
        return "AuthContext{actorId=" + actorId
                + ", handle=" + handle
                + ", actorType=" + actorType
                + ", kind=" + kind
                + ", deviceId=" + deviceId + '}';
    }
}
