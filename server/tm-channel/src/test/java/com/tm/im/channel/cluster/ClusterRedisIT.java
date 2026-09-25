package com.tm.im.channel.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集群路由与节点探活的<b>真实 Redis</b> 验证（DESIGN §7.4 / §10.4）。
 *
 * <p>这里验证的是单测替身结构上无法验证的东西：
 * <ul>
 *   <li><b>TTL 真的生效</b>：节点键过期后 {@code isAlive} 必须变成 false
 *       （替身里的「过期」是我自己写的，它永远同意我的假设）；</li>
 *   <li><b>键名就是契约</b>：{@code tm:route:{actorId}} / {@code tm:node:{nodeId}}
 *       是两个进程（可能还是两个版本）之间的约定。测试里直接写这些字面量，
 *       改名就会红；引用 {@code ClusterKeys} 则改名会跟着一起改，什么也钉不住；</li>
 *   <li><b>解绑的 CAS 语义</b>：客户端换节点重连时，旧节点不能删掉新节点刚写的绑定；</li>
 *   <li><b>指向已死节点的路由</b>：{@code locate} 必须返回空（判为「无人持有」），
 *       而那条路由键<b>刻意不删</b> —— 节点以同一个 nodeId 回来时它自动重新生效。</li>
 * </ul>
 *
 * <p>清理：节点键带 TTL，路由键按 actorId 精确删除。不用 FLUSHDB ——
 * 这是共用的开发 Redis，不清空别人的数据。
 *
 * <pre>
 *   uv run python tools/gen_runtime_config.py
 *   mvn -pl tm-channel -am test -Pit
 * </pre>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ClusterItConfig.class)
class ClusterRedisIT {

    /** 本次运行的唯一后缀：与其它测试运行、以及线上留下的键天然不重叠。 */
    private static final String RUN = Long.toString(System.nanoTime(), 36);

    @Autowired
    private StringRedisTemplate redis;

    // ------------------------------------------------------------------
    // 节点注册与探活
    // ------------------------------------------------------------------

    @Test
    @DisplayName("节点键带 TTL：过期之后 isAlive 必须变 false（否则崩溃的节点会被永久当成存活）")
    void nodeKeyExpiresOnItsTtl() throws Exception {
        String nodeId = nodeId("ttl");
        NodeRegistry nodes = new RedisNodeRegistry(redis);
        try {
            nodes.register(info(nodeId), Duration.ofSeconds(1));

            assertThat(redis.hasKey("tm:node:" + nodeId)).as("键名是跨进程契约，直接写死").isTrue();
            assertThat(nodes.isAlive(nodeId)).isTrue();

            Thread.sleep(1_400);

            assertThat(nodes.isAlive(nodeId))
                    .as("TTL 没设上（或设成「不过期」）时这里会是 true："
                            + "于是节点崩溃后，别的节点会一直把消息投进没人订阅的频道")
                    .isFalse();
            assertThat(nodes.aliveNodes()).extracting(NodeInfo::nodeId).doesNotContain(nodeId);
        } finally {
            redis.delete("tm:node:" + nodeId);
        }
    }

    @Test
    @DisplayName("续期只延长 TTL、不重写值；键不在了则返回 false（调用方据此重新注册）")
    void renewExtendsAndReportsMissingKeys() throws Exception {
        String nodeId = nodeId("renew");
        NodeRegistry nodes = new RedisNodeRegistry(redis);
        try {
            nodes.register(info(nodeId), Duration.ofSeconds(1));
            String raw = redis.opsForValue().get("tm:node:" + nodeId);

            assertThat(nodes.renew(nodeId, Duration.ofSeconds(4))).isTrue();
            Thread.sleep(1_400);

            assertThat(nodes.isAlive(nodeId)).as("续期后 1.4s 不该过期").isTrue();
            assertThat(redis.opsForValue().get("tm:node:" + nodeId))
                    .as("续期只延长 TTL，不动值：值里是节点自述（host/port/启动时间）")
                    .isEqualTo(raw);

            nodes.unregister(nodeId);
            assertThat(nodes.renew(nodeId, Duration.ofSeconds(4)))
                    .as("键没了必须返回 false —— 只会说 true 的续期会让本节点永远不被重新注册")
                    .isFalse();
        } finally {
            redis.delete("tm:node:" + nodeId);
        }
    }

    @Test
    @DisplayName("节点自述随重启更新；坏值只跳过不炸（诊断接口不该被一个脏键整批带崩）")
    void aliveNodesReadsDescriptorsAndSkipsGarbage() {
        String nodeId = nodeId("descr");
        String garbageId = nodeId("garbage");
        NodeRegistry nodes = new RedisNodeRegistry(redis);
        try {
            nodes.register(new NodeInfo(nodeId, "host-1", 8090, 1_700_000_000_000L), Duration.ofMinutes(1));
            nodes.register(new NodeInfo(nodeId, "host-1", 8091, 1_700_000_001_000L), Duration.ofMinutes(1));

            List<NodeInfo> mine = nodes.aliveNodes().stream()
                    .filter(i -> i.nodeId().equals(nodeId))
                    .toList();
            assertThat(mine).as("同一个 nodeId 重复注册只应有一条").hasSize(1);
            assertThat(mine.get(0).nettyPort()).as("重启后端口变了，描述必须跟着更新").isEqualTo(8091);
            assertThat(mine.get(0).startedAtMs()).isEqualTo(1_700_000_001_000L);

            // 人为写坏值：模拟旧版本格式，或有人手工改过这个键
            redis.opsForValue().set("tm:node:" + garbageId, "{ this is not json",
                    Duration.ofMinutes(1));
            assertThat(nodes.aliveNodes()).extracting(NodeInfo::nodeId)
                    .as("解析失败的键跳过即可，不能让整个诊断接口抛异常")
                    .doesNotContain(garbageId)
                    .contains(nodeId);
        } finally {
            redis.delete(List.of("tm:node:" + nodeId, "tm:node:" + garbageId));
        }
    }

    @Test
    @DisplayName("扫描用游标（SCAN）而不是 KEYS：小键空间下也不能漏键或死循环")
    void aliveNodesScansAllLiveNodes() {
        String nodeId = nodeId("scan");
        NodeRegistry nodes = new RedisNodeRegistry(redis);
        try {
            nodes.register(info(nodeId), Duration.ofMinutes(1));
            assertThat(nodes.aliveNodes()).extracting(NodeInfo::nodeId).contains(nodeId);
        } finally {
            redis.delete("tm:node:" + nodeId);
        }
    }

    // ------------------------------------------------------------------
    // Actor 路由
    // ------------------------------------------------------------------

    @Test
    @DisplayName("自己持有的 Actor：本节点没注册（或注册失败）时，locate 也必须返回自己")
    void locateReturnsSelfWithoutConsultingLiveness() {
        long actorId = actorId("self");
        NodeRegistry nodes = new RedisNodeRegistry(redis);
        String nodeA = nodeId("self-a");
        ActorRouteTable tableA = new RedisActorRouteTable(redis, nodes, identity(nodeA));
        try {
            // 刻意不注册 nodeA：本进程当然活着，没必要为此多一次 Redis 往返，
            // 更不能因为一次注册失败就把自己的连接判成「不在线」。
            tableA.bind(actorId);

            assertThat(redis.opsForValue().get("tm:route:" + actorId))
                    .as("键名与值是跨节点契约：tm:route:{actorId} → nodeId")
                    .isEqualTo(nodeA);
            assertThat(tableA.locate(actorId)).contains(nodeA);
        } finally {
            redis.delete("tm:route:" + actorId);
        }
    }

    @Test
    @DisplayName("别的节点存活 → 查得到路由；该节点失活 → 判为无人持有，路由键留着等它自愈")
    void locateTrustsOnlyAliveNodesAndHealsAfterRestart() throws Exception {
        long actorId = actorId("remote");
        NodeRegistry nodes = new RedisNodeRegistry(redis);
        String nodeA = nodeId("rmt-a");
        String nodeB = nodeId("rmt-b");
        ActorRouteTable tableA = new RedisActorRouteTable(redis, nodes, identity(nodeA));
        ActorRouteTable tableB = new RedisActorRouteTable(redis, nodes, identity(nodeB));
        try {
            nodes.register(toInfo(identity(nodeA), 1L), Duration.ofMinutes(1));
            nodes.register(toInfo(identity(nodeB), 2L), Duration.ofSeconds(1));
            tableB.bind(actorId);

            assertThat(tableA.locate(actorId)).as("B 还活着，路由指向 B").contains(nodeB);

            Thread.sleep(1_400);   // 等 B 的节点键过期

            assertThat(tableA.locate(actorId))
                    .as("持有者已死 → 必须判为「无人持有」：否则消息会被投进没人订阅的频道，"
                            + "而发送方还以为推送成功了")
                    .isEmpty();
            assertThat(redis.opsForValue().get("tm:route:" + actorId))
                    .as("路由键刻意不删（删了就不能自愈）：它指向的节点回来后，老路由自动重新生效")
                    .isEqualTo(nodeB);

            nodes.register(toInfo(identity(nodeB), 3L), Duration.ofMinutes(1));
            assertThat(tableA.locate(actorId))
                    .as("B 以同一个 nodeId 回来（进程重启/短时失联）→ 那些仍连着的连接立刻重新可路由")
                    .contains(nodeB);
        } finally {
            redis.delete(List.of("tm:route:" + actorId,
                    "tm:node:" + nodeA, "tm:node:" + nodeB));
        }
    }

    @Test
    @DisplayName("解绑是 CAS：旧节点不得删掉新节点刚写入的绑定（否则该用户在线却收不到推送，且无法自愈）")
    void unbindOnlyDeletesOwnRoute() {
        long actorId = actorId("cas");
        NodeRegistry nodes = new RedisNodeRegistry(redis);
        String nodeA = nodeId("cas-a");
        String nodeB = nodeId("cas-b");
        ActorRouteTable tableA = new RedisActorRouteTable(redis, nodes, identity(nodeA));
        ActorRouteTable tableB = new RedisActorRouteTable(redis, nodes, identity(nodeB));
        try {
            // A 上登录过，随后客户端换到 B（而 A 那条旧连接的清理动作更晚才发生）
            tableA.bind(actorId);
            tableB.bind(actorId);

            assertThat(tableA.unbind(actorId)).as("路由已指向 B，A 无权删除").isFalse();
            assertThat(tableB.locate(actorId)).contains(nodeB);

            assertThat(tableB.unbind(actorId)).as("持有者自己才能删").isTrue();
            assertThat(tableB.unbind(actorId)).as("重复解绑必须幂等（返回 false 而不是抛）").isFalse();
            assertThat(redis.hasKey("tm:route:" + actorId)).isFalse();
        } finally {
            redis.delete("tm:route:" + actorId);
        }
    }

    @Test
    @DisplayName("配置模板里的默认值 Redis 认：ttl=45s 的节点键不会立刻消失，路由可用")
    void defaultTtlFromPropertiesIsUsable() {
        long actorId = actorId("default");
        NodeProperties properties = new NodeProperties();
        properties.validate();
        NodeRegistry nodes = new RedisNodeRegistry(redis);
        String nodeId = nodeId("dflt");
        ActorRouteTable table = new RedisActorRouteTable(redis, nodes, identity(nodeId));
        try {
            nodes.register(new NodeInfo(nodeId, "host-d", 8090, System.currentTimeMillis()),
                    properties.getTtl());
            table.bind(actorId);

            assertThat(redis.getExpire("tm:node:" + nodeId))
                    .as("TTL 应当接近 45s（用配置值写入，而不是硬编码）")
                    .isBetween(40L, 46L);
            assertThat(table.locate(actorId)).contains(nodeId);
        } finally {
            redis.delete(List.of("tm:route:" + actorId, "tm:node:" + nodeId));
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static String nodeId(String name) {
        return "it-" + RUN + "-" + name;
    }

    /** 与真实 actorId（雪花 id）区间不同，人工排查时一眼看出是测试留下的。 */
    private static long actorId(String name) {
        return 9_000_000_000L + Math.abs((RUN + name).hashCode() % 100_000_000);
    }

    private static NodeIdentity identity(String nodeId) {
        return new NodeIdentity(nodeId, "it-host", 8090);
    }

    private static NodeInfo info(String nodeId) {
        return new NodeInfo(nodeId, "it-host", 8090, System.currentTimeMillis());
    }

    private static NodeInfo toInfo(NodeIdentity identity, long startedAtMs) {
        return new NodeInfo(identity.nodeId(), identity.host(), identity.nettyPort(), startedAtMs);
    }
}
