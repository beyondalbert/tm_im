package com.tm.im.channel.cluster;

import com.tm.im.channel.session.ConnectionRegistry;
import com.tm.im.channel.session.TmConnection;
import com.tm.im.proto.transport.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * 给本地连接注册表挂上集群语义：<b>连接建立时发布路由，连接消失时释放路由</b>。
 *
 * <p><b>为什么用装饰器而不是在 {@code AuthHandler} 里直接调路由表</b>：
 * 「本地注册」与「路由发布」之间有一条必须成立的不变式 ——
 * 本地表里有这个人，Redis 里的路由才允许指向本节点。把它写成一个包装类，
 * 这条不变式就只有一处实现；写在 handler 里则是两处（成功路径与断开路径），
 * 而将来任何一条新的注册路径（比如 HTTP 短连接同样注册在线状态）
 * 都会漏掉其中一半。漏掉的后果不是报错，而是「消息投到了一个没有该连接的节点上，
 * 发送方却报告成功」。
 *
 * <p>顺序也是这条不变式的一部分：先本地注册、再发布路由，最后才回 AUTH_OK
 * （AUTH_OK 的写入在 {@code AuthHandler} 里，位于 {@link #register} 之后）。
 * 反过来的话，客户端收到 AUTH_OK 立刻发消息，而此刻别的节点查到的路由还可能
 * 指向它上一次登录的节点 —— 那台机器上没有连接，消息白推一次。
 *
 * <p><b>Redis 故障时不能让登录失败</b>：路由写不进去，损失的只是「别的节点能把消息
 * 直接推给他」，他的连接照样建立、消息照样落库、重连后照样按 {@code last_seq} 补齐。
 * 反过来让鉴权失败，等于把一次 Redis 抖动放大成「全站登不上」。
 * 因此这里吞掉异常并告警 —— 这是<b>有意的降级</b>，不是漏了异常处理。
 */
public class ClusterAwareConnectionRegistry implements ConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ClusterAwareConnectionRegistry.class);

    private final ConnectionRegistry local;
    private final ActorRouteTable routes;

    public ClusterAwareConnectionRegistry(ConnectionRegistry local, ActorRouteTable routes) {
        this.local = local;
        this.routes = routes;
    }

    @Override
    public int register(TmConnection connection) {
        int evicted = local.register(connection);
        long actorId = connection.actorId();
        try {
            routes.bind(actorId);
        } catch (RuntimeException e) {
            log.warn("本地已上线但集群路由发布失败 actorId={}：跨节点推送将退化为「等对方重连后 SYNC 补齐」，"
                    + "本机推送不受影响。cause={}", actorId, e.toString());
        }
        return evicted;
    }

    @Override
    public void unregister(TmConnection connection) {
        local.unregister(connection);
        long actorId = connection.actorId();
        if (local.isOnline(actorId)) {
            // 顶号：这个 Actor 在本节点仍有连接（新连接），路由必须留给它。
            // 这里正是「无条件删路由」会出错的地方 —— 旧连接的清理动作
            // 会把新连接的路由抹掉，而该 Actor 之后的跨节点推送全部落空。
            log.debug("旧连接下线但 actorId={} 在本节点仍在线（顶号）：保留路由", actorId);
            return;
        }
        try {
            routes.unbind(actorId);
        } catch (RuntimeException e) {
            // 与注册同理：删不掉只是留下一条指向本节点的陈旧路由，
            // 而那条路由会被「目标节点已死 / 本节点没有该连接」两条判据挡住。
            log.warn("连接已下线但集群路由释放失败 actorId={}：留下一条陈旧路由，"
                    + "它指向本节点而本节点已无该连接（推送会被判为对方离线）。cause={}",
                    actorId, e.toString());
        }
    }

    @Override
    public int push(long actorId, Frame frame) {
        return local.push(actorId, frame);
    }

    @Override
    public void broadcast(Frame frame) {
        local.broadcast(frame);
    }

    @Override
    public boolean isOnline(long actorId) {
        return local.isOnline(actorId);
    }

    @Override
    public int size() {
        return local.size();
    }

    @Override
    public Collection<TmConnection> connections() {
        return local.connections();
    }

    @Override
    public long droppedFrames() {
        return local.droppedFrames();
    }
}
