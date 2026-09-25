package com.tm.im.channel.cluster;

import java.time.Duration;
import java.util.List;

/**
 * 节点注册与探活（DESIGN §7.4 / §10.4，Redis {@code tm:node:{nodeId}}）。
 *
 * <p>它回答的问题是「某个 nodeId 现在还算数吗」。没有它，路由表只能表达
 * 「某个 Actor 曾经挂在 node-7」，而不能表达「node-7 已经崩了」——
 * 于是消息会一直被投到一个没人订阅的频道里，发送方还报告成功。
 *
 * <p>TTL 与心跳是唯一的判活手段（不做主动探测：探测需要知道每个节点的地址，
 * 而节点地址在容器编排下随时会变，判活的复杂度会立刻超过它带来的价值）。
 */
public interface NodeRegistry {

    /**
     * 注册／覆盖本节点（或任意节点）的信息，并设置存活 TTL。
     *
     * @param ttl 过期时间；每次调用都会重置
     */
    void register(NodeInfo info, Duration ttl);

    /**
     * 续期。
     *
     * @return false 表示键已不存在（Redis 重启、被运维清理、或本节点曾长时间失联）——
     *         调用方必须据此重新 {@link #register} 一次，否则「本节点仍活着」
     *         这个事实再也不会回到 Redis 里
     */
    boolean renew(String nodeId, Duration ttl);

    /** 优雅下线：立刻让其它节点停止把消息投向本节点。 */
    void unregister(String nodeId);

    /** 该节点是否存活（键存在且未过期）。 */
    boolean isAlive(String nodeId);

    /** 当前存活的所有节点，供运维与诊断使用。 */
    List<NodeInfo> aliveNodes();
}
