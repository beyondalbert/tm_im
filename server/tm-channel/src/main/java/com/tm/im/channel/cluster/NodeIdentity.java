package com.tm.im.channel.cluster;

/**
 * 本节点的身份：{@code nodeId} + 对外可读的位置信息。
 *
 * <p>{@code nodeId} 是 Redis 里两处键的一部分（{@code tm:node:{nodeId}}、
 * {@code tm:route:{actorId}} 的值），因此它必须满足两个条件：
 * <ul>
 *   <li><b>每个进程唯一</b>：两个实例用同一个 nodeId，会让路由指向错误的进程 ——
 *       推送被投到一台没有该连接的机器上，SendOutcome 却报告「已推送」。
 *       这与 Snowflake 节点号相撞是同一类缺陷（见 {@code SnowflakeProperties}）。</li>
 *   <li><b>重启后不变</b>：变了也不报错，只是进程重启后它自己留下的那些老路由
 *       永远不会再被认领（{@code tm:route:*} 是不带 TTL 的，见 {@link RedisActorRouteTable}）。</li>
 * </ul>
 *
 * <p>默认按「主机名:Netty 端口」推导：同机多实例必然用不同端口，跨机则主机名不同，
 * 于是单机开发与常规多实例部署都不需要额外配置。显式配置的意义在于
 * 「主机名:端口」不足以区分时（容器编排把同一个名字与端口分给多次部署）。
 */
public record NodeIdentity(String nodeId, String host, int nettyPort) {

    public NodeIdentity {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId 不能为空");
        }
    }

    @Override
    public String toString() {
        return nodeId + "@" + host + ":" + nettyPort;
    }
}
