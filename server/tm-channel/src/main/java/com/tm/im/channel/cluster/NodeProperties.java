package com.tm.im.channel.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

/**
 * 集群节点标识与探活配置（{@code tm.node.*}，DESIGN §7.4）。
 *
 * <p>三个值共同定义了「一个节点怎么被别的节点看见、又怎么被判死」：
 * <pre>
 * nodeId      → tm:node:{nodeId} 与 tm:route:{actorId} 的值
 * ttl         → tm:node:{nodeId} 的过期时间：过期即被视为已死
 * heartbeat   → 续期间隔，必须显著小于 ttl
 * </pre>
 *
 * <p><b>为什么心跳间隔必须小于 TTL，而且这里直接拒绝不合法组合</b>：
 * 若 {@code heartbeat >= ttl}，心跳键在两次续期之间必然过期 —— 于是本节点
 * 每隔一会儿就被别的节点判死一次，表现是「跨节点推送间歇性失效」，
 * 而重启、扩容、连接数都正常，排查会从网络一路查到客户端。
 * 反过来把 TTL 调得极大（比如 1 小时）也不对：节点崩溃后，其它节点会继续
 * 把消息发给它的频道（没人订阅），消息只能等接收方重连时靠 SYNC 补 ——
 * 判死越慢，「降级为 SYNC 补齐」持续得越久。
 *
 * <p><b>{@code id} 的默认值是字面量 {@code auto}</b>（而不是「空串」或「未配置」）：
 * 配置模板里写成 {@code ${TM_NODE_ID:auto}}，两者必须能对上，
 * 由 {@code tools/verify_config_template.py} 机器校验。空串在 YAML 里常有歧义
 * （被引号包住是空串、不引号是 null），而 {@code auto} 只有一个意思。
 */
@ConfigurationProperties(prefix = "tm.node")
public class NodeProperties {

    /** 未显式配置时的取值：按「主机名:Netty 端口」推导。 */
    public static final String AUTO = "auto";

    private static final Logger log = LoggerFactory.getLogger(NodeProperties.class);

    /**
     * 本节点标识；字面量 {@code auto} 表示按主机名与 Netty 端口推导。
     *
     * <p>初值写成字面量而不是上面的 {@link #AUTO} 常量：配置模板一致性校验
     * （{@code tools/verify_config_template.py}）是<b>读源码</b>比默认值的，
     * 只能认出字符串字面量。两者相等这件事由单测钉住（{@code NodePropertiesTest}）。
     */
    private String id = "auto";

    /**
     * 节点存活 TTL（{@code tm:node:{nodeId}} 的过期时间）。
     *
     * <p>它同时是「节点崩溃到别人发现」的探活延迟上界：TTL 内路由仍然被信任，
     * 发往该节点的消息进入无人订阅的频道被丢弃（不报错），由接收方重连后的
     * SYNC 补齐。所以这个值是一次取舍：越小越早停止无效投递，越大越能容忍
     * 网络抖动与 Redis 抖动。
     */
    private Duration ttl = Duration.ofSeconds(45);

    /** 心跳续期间隔（秒）。默认 15s：TTL 45s 意味着连丢 2 次续期才会被判死。 */
    private int heartbeatSeconds = 15;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public int getHeartbeatSeconds() {
        return heartbeatSeconds;
    }

    public void setHeartbeatSeconds(int heartbeatSeconds) {
        this.heartbeatSeconds = heartbeatSeconds;
    }

    /**
     * 解析出本节点身份（使用真实主机名）。
     *
     * @param nettyPort Netty 监听端口；配置成 0 时它是一个「稍后才确定」的值，
     *                  因此调用方必须在真正绑定之后才解析身份 ——
     *                  用 0 推导出来的 nodeId 会让所有节点撞成同一个
     */
    public NodeIdentity resolve(int nettyPort) {
        return resolve(hostname(), nettyPort);
    }

    /**
     * 解析出本节点身份（主机名由调用方给出，便于测试）。
     *
     * <p>纯函数：不读环境、不碰网络，因此「{@code auto} 展开成什么」这条规则
     * 可以被真正钉住（而不是靠一台具体机器的 hostname 去碰运气）。
     */
    public NodeIdentity resolve(String hostname, int nettyPort) {
        validate();
        String explicit = id == null ? "" : id.trim();
        boolean auto = explicit.isEmpty() || AUTO.equalsIgnoreCase(explicit);
        String nodeId = auto ? hostname + ":" + nettyPort : explicit;
        if (auto) {
            log.warn("未配置 tm.node.id，按「主机名:端口」推导出 nodeId={}。"
                            + "同主机多实例、或容器编排把同一主机名与端口分给多次部署时，"
                            + "必须显式配置（TM_NODE_ID）且各不相同："
                            + "nodeId 相撞会让 tm:route 指向错误的进程", nodeId);
        }
        return new NodeIdentity(nodeId, hostname, nettyPort);
    }

    /**
     * 校验组合是否自洽。非法组合直接启动失败，而不是「按某个解释继续跑」——
     * 心跳与 TTL 的关系属于「不写出来没人会想到」的那类约束，
     * 让它以启动失败的形式出现，比让它在凌晨表现为间歇性推送丢失要好得多。
     */
    public void validate() {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("tm.node.ttl 必须 > 0，实际 " + ttl);
        }
        if (heartbeatSeconds <= 0) {
            throw new IllegalStateException("tm.node.heartbeat-seconds 必须 > 0，实际 " + heartbeatSeconds);
        }
        long heartbeatMillis = heartbeatSeconds * 1000L;
        if (heartbeatMillis >= ttl.toMillis()) {
            throw new IllegalStateException(
                    "tm.node.heartbeat-seconds(" + heartbeatSeconds + "s) 必须小于 tm.node.ttl(" + ttl
                            + ")：否则心跳键在两次续期之间必然过期，"
                            + "本节点会被别的节点反复判死 —— 表现是跨节点推送间歇性失效，"
                            + "而资源、连接数、日志都没有任何异常");
        }
        if (heartbeatMillis * 3 > ttl.toMillis()) {
            log.warn("tm.node.ttl({}) 不足 3 个心跳间隔({}s)：丢掉 1 次续期就会被判死。"
                            + "建议 ttl ≥ 3 × heartbeat-seconds", ttl, heartbeatSeconds);
        }
    }

    /** 真实主机名；取不到时退化，但不让「取不到主机名」变成启动失败。 */
    static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            // 容器里 /etc/hosts 没配好、或 DNS 不可用时会走到这里。
            // 此时 nodeId 会退化成 hostname 环境变量（通常是编排系统给的 pod 名），
            // 仍然满足「每实例唯一」这个真正的要求。
            String fallback = System.getenv("HOSTNAME");
            if (fallback == null || fallback.isBlank()) {
                fallback = System.getenv("COMPUTERNAME");
            }
            String host = (fallback == null || fallback.isBlank()) ? "unknown-host" : fallback.trim();
            log.warn("无法解析本机主机名（{}），改用 {} 作为 nodeId 前缀。"
                    + "若多个实例都落到 unknown-host，它们的 nodeId 会相撞 —— 请显式配置 TM_NODE_ID",
                    e.toString(), host);
            return host;
        }
    }
}
