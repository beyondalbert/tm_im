package com.tm.im.channel.cluster;

/**
 * 集群键名（DESIGN §10.4）。集中在一处而不是散落在各实现里。
 *
 * <p><b>为什么值得单独一个类</b>：键名是<b>跨进程、跨版本</b>的契约。滚动升级期间
 * 新旧两个版本的节点会同时在跑，它们必须对「同一个 Actor 的路由存在哪个键上」
 * 达成一致 —— 而这一点没有任何编译期检查。两处各写一遍字符串，改一处忘一处时
 * 的表现是「路由查不到、推送静默降级成靠 SYNC 补齐」：功能看起来还在工作，
 * 只是慢，没人会去查键名。
 */
final class ClusterKeys {

    private static final String ROUTE_PREFIX = "tm:route:";
    private static final String NODE_PREFIX = "tm:node:";

    private ClusterKeys() {
    }

    /** 谁持有这个 Actor 的连接 → nodeId。 */
    static String route(long actorId) {
        return ROUTE_PREFIX + actorId;
    }

    /** 该节点的心跳键，带 TTL；过期即视为该节点已死。 */
    static String node(String nodeId) {
        return NODE_PREFIX + nodeId;
    }

    /** 节点键的扫描模式（供运维与测试枚举在线节点）。 */
    static String nodePattern() {
        return NODE_PREFIX + "*";
    }
}
