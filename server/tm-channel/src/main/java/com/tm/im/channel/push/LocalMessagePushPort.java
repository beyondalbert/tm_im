package com.tm.im.channel.push;

import com.tm.im.channel.codec.MessageMapper;
import com.tm.im.channel.session.ConnectionRegistry;
import com.tm.im.core.channel.MessagePushPort;
import com.tm.im.domain.entity.Message;
import org.springframework.stereotype.Component;

/**
 * {@link MessagePushPort} 的本节点实现。
 *
 * <p>M2 只做本节点投递：{@code registry.push} 在注册表里查 actorId，
 * 命中则写 Channel。跨节点投递（DESIGN §7.4 的 Redis 路由 + Pub/Sub）
 * 是后续实现，接口语义已经预留 —— 调用方本来就应当容忍返回 0
 * （「对方离线」与「对方在别的节点」对调用方是同一件事：
 * 消息已落库，等对方重连时走 SYNC 补齐）。
 */
@Component
public class LocalMessagePushPort implements MessagePushPort {

    private final ConnectionRegistry registry;
    private final MessageMapper mapper;

    public LocalMessagePushPort(ConnectionRegistry registry, MessageMapper mapper) {
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
