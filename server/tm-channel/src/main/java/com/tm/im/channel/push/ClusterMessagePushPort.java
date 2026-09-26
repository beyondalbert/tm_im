package com.tm.im.channel.push;

import com.tm.im.channel.cluster.NodeIdentity;
import com.tm.im.channel.cluster.NodePublisher;
import com.tm.im.channel.cluster.RedisPushBus;
import com.tm.im.channel.codec.TransportMessageMapper;
import com.tm.im.channel.session.ConnectionRegistry;
import com.tm.im.core.channel.MessagePushPort;
import com.tm.im.domain.entity.Message;
import com.tm.im.proto.transport.Frame;

import java.util.Optional;

/**
 * {@link MessagePushPort} 的集群实现：<b>先本节点、再跨节点</b>（DESIGN §7.4）。
 *
 * <pre>
 *   pushToActor(actorId)
 *     ├─ 本地连接表命中        → 直接写 Channel，返回 1
 *     ├─ 本地没有 + 路由指向别的节点 → 发布到 tm:push:{那个节点}，返回 1
 *     └─ 本地没有 + 没有路由（或路由指向本节点却没有连接）→ 返回 0（离线）
 * </pre>
 *
 * <p><b>顺序不能反</b>：先查路由再写本地表的话，会在「路由还没发布完（或发布失败）
 * 但连接已经在本地表里」的窗口里把消息投出去——那条路走的是 Redis 与另一个节点，
 * 而对方明明就在本地。反过来（先本地）最坏的结果是「本节点没有、发布给别的节点」，
 * 而那个判断依据（本地表）永远是新鲜的。
 *
 * <p><b>「路由指向自己」也要返回 0</b>：那是一条陈旧路由（连接已断，但解绑失败——
 * 例如 Redis 抖动时 {@code unbind} 抛了异常，见 {@code ClusterAwareConnectionRegistry}）。
 * 若在这里发布，就会往自己的频道投一帧，而收件人（自己）也会发现没有那条连接
 * ——一次无用的 Redis 往返，并且在日志里表现为「收到了一个发给自己的推送」。
 *
 * <p><b>返回值语义</b>：{@code 0} = 无处可投（离线）；{@code ≥1} = 已投出。
 * 跨节点那一路的 {@code 1} 只表示「已发布给目标节点」，不表示对方已收到
 * ——发布本身就是 fire-and-forget（理由见 {@link RedisPushBus}）。
 * 调用方（{@code MessageService}）只用它做日志与统计，从不据此判断成败。
 */
public class ClusterMessagePushPort implements MessagePushPort {

    private final LocalMessagePushPort local;
    private final ConnectionRegistry registry;
    private final com.tm.im.channel.cluster.ActorRouteTable routes;
    private final NodePublisher bus;
    private final NodeIdentity self;
    private final TransportMessageMapper mapper;

    public ClusterMessagePushPort(LocalMessagePushPort local,
                                  ConnectionRegistry registry,
                                  com.tm.im.channel.cluster.ActorRouteTable routes,
                                  NodePublisher bus,
                                  NodeIdentity self,
                                  TransportMessageMapper mapper) {
        this.local = local;
        this.registry = registry;
        this.routes = routes;
        this.bus = bus;
        this.self = self;
        this.mapper = mapper;
    }

    @Override
    public int pushToActor(long actorId, Message message) {
        Frame frame = mapper.pushFrame(message);

        int written = registry.push(actorId, frame);
        if (written > 0) {
            return written;
        }

        Optional<String> holder = routes.locate(actorId);
        if (holder.isEmpty()) {
            return 0;
        }
        if (holder.get().equals(self.nodeId())) {
            // 陈旧路由：指向本节点，而本节点已经没有这条连接（见类注释）。
            return 0;
        }
        return bus.publish(holder.get(), actorId, frame) ? 1 : 0;
    }

    /**
     * 该 Actor 是否（可能）在线：本节点有连接，或者路由指向某个存活节点。
     *
     * <p>它比 {@link #pushToActor} 更弱——「路由存在」不等于「连接还在」
     * （见 {@code ActorRouteTable.locate} 的说明）。因此这个方法的语义是
     * 「值得起一次推送」，而不是「一定能推达」。调用方用它做短路判断即可，
     * 不要用它做任何正确性决策。
     */
    @Override
    public boolean isOnline(long actorId) {
        return registry.isOnline(actorId) || routes.locate(actorId).isPresent();
    }

    @Override
    public int localConnectionCount() {
        return registry.size();
    }

    /** 本节点直写的实现（跨节点那一路由 {@link RedisPushBus} 完成）。 */
    public LocalMessagePushPort local() {
        return local;
    }
}
