package com.tm.im.channel.cluster;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link ActorRouteTable} 的测试替身：记录「谁被绑定／释放了」。
 *
 * <p>不连 Redis —— 本类要验证的是<b>何时</b>绑定与释放（鉴权成功之后、连接真正消失
 * 之后），而不是「Redis 里的键长什么样」。后者由 {@code ClusterRedisIT} 用真实
 * Redis 验证。两者刻意分开：把 Redis 拖进单元测试，会让「路由语义错了」和
 * 「Redis 连不上」变成同一种红灯。
 *
 * <p>调用顺序也要记：路由必须先于 AUTH_OK 生效，见
 * {@link ClusterAwareConnectionRegistry} 的类注释。
 */
public class RecordingActorRouteTable implements ActorRouteTable {

    private final String selfNodeId;
    private final Map<Long, String> routes = new ConcurrentHashMap<>();
    private final List<Long> bindCalls = new CopyOnWriteArrayList<>();
    private final List<Long> unbindCalls = new CopyOnWriteArrayList<>();

    private volatile boolean failBind;
    private volatile boolean failUnbind;

    public RecordingActorRouteTable() {
        this("recording-node");
    }

    public RecordingActorRouteTable(String selfNodeId) {
        this.selfNodeId = selfNodeId;
    }

    public void failBindAlways() {
        failBind = true;
    }

    public void failUnbindAlways() {
        failUnbind = true;
    }

    /**
     * 恢复。端到端测试里服务端是静态共用的：一个用例把路由弄成「必失败」，
     * 后面任何检查路由的用例都会红在一个与它无关的地方。
     */
    public void resetFailures() {
        failBind = false;
        failUnbind = false;
    }

    @Override
    public void bind(long actorId) {
        if (failBind) {
            throw new IllegalStateException("模拟 Redis 不可用");
        }
        bindCalls.add(actorId);
        routes.put(actorId, selfNodeId);
    }

    @Override
    public boolean unbind(long actorId) {
        if (failUnbind) {
            throw new IllegalStateException("模拟 Redis 不可用");
        }
        unbindCalls.add(actorId);
        return routes.remove(actorId, selfNodeId);
    }

    @Override
    public Optional<String> locate(long actorId) {
        return Optional.ofNullable(routes.get(actorId));
    }

    /** 当前是否有一条指向本节点的路由。 */
    public boolean isBound(long actorId) {
        return selfNodeId.equals(routes.get(actorId));
    }

    public List<Long> bindCalls() {
        return List.copyOf(bindCalls);
    }

    public List<Long> unbindCalls() {
        return List.copyOf(unbindCalls);
    }

    /** 直接把一条指向别处的路由塞进去，用于模拟「这个 Actor 现在挂在别的节点」。 */
    public void routeTo(long actorId, String nodeId) {
        routes.put(actorId, nodeId);
    }
}
