package com.tm.im.channel.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * {@link ActorRouteTable} 的 Redis 实现（{@code tm:route:{actorId}} → nodeId，STRING）。
 *
 * <p>两个刻意的设计决定，都是「不写下来将来一定会被改回去」的那类：
 *
 * <p><b>1. 路由键不带 TTL，判活交给 {@code tm:node:{nodeId}}</b>。
 * 直觉上应该给每个路由键一个短 TTL（比如 90s）并随心跳续期，但那条路走不通：
 * 每个节点几十万条连接，逐条续期就是几十万次写／周期，而<b>不续期</b>会让
 * 「长时间没在别处被推送过的在线用户」的路由悄悄过期 —— 他还连着，
 * 却再也收不到跨节点推送。用「键不设过期 + 目标节点是否存活」来判断，
 * 每次查询只多一次 {@code EXISTS}，而结果与逐条续期等价。
 *
 * <p>代价是：崩掉的节点会永久留下它那些 Actor 的路由键（每个几十字节）。
 * 这里<b>刻意不在查询时删</b>这些陈旧键：删了就不能自愈 ——
 * 节点短时失联（Redis 抖动、网络抖动、GC 停顿超过 TTL）后重新注册同一个 nodeId 时，
 * 老路由会自动重新生效；删掉的话，那些在线用户的跨节点推送要等到他们下次重连
 * 才会恢复。真正死亡（换了 nodeId）的节点留下的键由运维清理
 * （{@code NodeRegistry.aliveNodes()} 能列出存活节点，据此可判断哪些 nodeId 已不再出现）。
 *
 * <p><b>2. 解绑是 CAS</b>（Lua 保证「值仍是自己才删」）。原因见
 * {@link ActorRouteTable#unbind(long)}：客户端换节点重连时，
 * 新节点的绑定与旧节点的清理没有顺序保证，无条件删会删掉刚写入的新绑定。
 */
@Component
public class RedisActorRouteTable implements ActorRouteTable {

    private static final Logger log = LoggerFactory.getLogger(RedisActorRouteTable.class);

    /**
     * 「值仍是本节点才删除」。用 Lua 而不是 {@code GET} + {@code DEL}：
     * 两条命令之间的窗口正是「新节点刚绑定」的那个瞬间，而那条绑定一旦被删，
     * 该 Actor 在其连接存活期间会一直收不到跨节点推送（无法自愈）。
     */
    private static final RedisScript<Long> UNBIND_IF_OWNER = RedisScript.of(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) "
                    + "else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final NodeRegistry nodes;
    private final NodeIdentity self;

    public RedisActorRouteTable(StringRedisTemplate redis, NodeRegistry nodes, NodeIdentity self) {
        this.redis = redis;
        this.nodes = nodes;
        this.self = self;
    }

    @Override
    public void bind(long actorId) {
        redis.opsForValue().set(ClusterKeys.route(actorId), self.nodeId());
    }

    @Override
    public boolean unbind(long actorId) {
        Long deleted = redis.execute(UNBIND_IF_OWNER, List.of(ClusterKeys.route(actorId)), self.nodeId());
        return deleted != null && deleted > 0;
    }

    @Override
    public Optional<String> locate(long actorId) {
        String nodeId = redis.opsForValue().get(ClusterKeys.route(actorId));
        if (nodeId == null || nodeId.isBlank()) {
            return Optional.empty();
        }
        if (nodeId.equals(self.nodeId())) {
            // 自己一定活着：这条连接就在本进程里，不需要（也不应该）去问 Redis。
            return Optional.of(nodeId);
        }
        if (nodes.isAlive(nodeId)) {
            return Optional.of(nodeId);
        }
        log.debug("路由指向已失活的节点 actorId={} nodeId={}：按未持有处理（消息等对方重连后 SYNC 补齐）",
                actorId, nodeId);
        return Optional.empty();
    }
}
