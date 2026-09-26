package com.tm.im.channel.cluster;

import com.tm.im.proto.transport.Frame;

/**
 * 「把一帧投给另一个节点」——跨节点投递的<b>唯一</b>协作面（实现：{@link RedisPushBus}）。
 *
 * <p>为什么单独一个接口，而不是直接依赖 {@link RedisPushBus}：推送网关
 * （{@code ClusterMessagePushPort}）需要判断的只是「本节点能不能直写」与
 * 「路由指向谁」，而 {@code publish} 那一行是它唯一需要的协作点。
 * 依赖整个总线意味着测试要先造出一个 Redis 连接工厂与一个监听容器
 * ——而那条路径的正确性由真实 Redis 的集成测试覆盖（{@code PushBusRedisIT}），
 * 两侧各自测自己那一半。
 *
 * <p><b>{@code publish} 返回 true 只表示「Redis 收下了」</b>，不表示对方已收到
 * （见 {@link RedisPushBus} 的类注释）。
 */
@FunctionalInterface
public interface NodePublisher {

    boolean publish(String nodeId, long actorId, Frame frame);
}
