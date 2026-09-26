package com.tm.im.channel.push;

import com.tm.im.channel.codec.TransportMessageMapper;
import com.tm.im.channel.session.ConnectionRegistry;
import com.tm.im.core.channel.MessagePushPort;
import com.tm.im.domain.entity.Message;

/**
 * {@link MessagePushPort} 的本节点实现。
 *
 * <p>M2 只做本节点投递：{@code registry.push} 在注册表里查 actorId，
 * 命中则写 Channel。
 *
 * <p><b>它不是容器里的那个 Bean</b>：容器里暴露的是
 * {@link ClusterMessagePushPort}（本类 + Redis 路由 + Pub/Sub）。
 * 这里做成普通类而不是 {@code @Component}，否则按类型注入会有两个候选，
 * 应用启动即报歧义 —— 与 {@code LocalConnectionRegistry} 同一做法。
 */
public class LocalMessagePushPort implements MessagePushPort {

    private final ConnectionRegistry registry;
    private final TransportMessageMapper mapper;

    public LocalMessagePushPort(ConnectionRegistry registry, TransportMessageMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    @Override
    public int pushToActor(long actorId, Message message) {
        return registry.push(actorId, mapper.pushFrame(message));
    }

    @Override
    public boolean isOnline(long actorId) {
        return registry.isOnline(actorId);
    }

    @Override
    public int localConnectionCount() {
        return registry.size();
    }
}
