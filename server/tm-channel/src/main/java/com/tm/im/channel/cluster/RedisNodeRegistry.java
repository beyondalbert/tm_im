package com.tm.im.channel.cluster;

import com.tm.im.common.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link NodeRegistry} 的 Redis 实现（{@code tm:node:{nodeId}}，STRING + TTL）。
 *
 * <p>过期时间交给 Redis 自己管，而不是「软件过期」（写入时间戳、读的时候比较）：
 * 判活这件事最怕的就是「写入方与读取方的时钟不一致」，用 Redis 的 TTL 就没有时钟问题，
 * 而且一个崩溃的进程不会留下任何后台任务去清理自己的键。
 *
 * <p>{@link #renew} 用 {@code EXPIRE} 而不是重写整个值：心跳每 15s 一次、每个节点一次，
 * 重写整个 JSON 只是多传几十字节，但 {@code EXPIRE} 只在键存在时生效 ——
 * 它天然回答了「我还在吗」这个问题。返回 false 时调用方会重新注册一次，
 * 于是「Redis 重启过 / 键被运维清了」这种情况会自愈。
 */
@Component
public class RedisNodeRegistry implements NodeRegistry {

    private static final Logger log = LoggerFactory.getLogger(RedisNodeRegistry.class);

    /** 扫描批次：一次拿太多键会让 Redis 在这条命令上停顿，进而拖慢整个集群。 */
    private static final int SCAN_COUNT = 200;

    private final StringRedisTemplate redis;

    public RedisNodeRegistry(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void register(NodeInfo info, Duration ttl) {
        redis.opsForValue().set(ClusterKeys.node(info.nodeId()), Json.write(info), ttl);
    }

    @Override
    public boolean renew(String nodeId, Duration ttl) {
        return Boolean.TRUE.equals(redis.expire(ClusterKeys.node(nodeId), ttl));
    }

    @Override
    public void unregister(String nodeId) {
        redis.delete(ClusterKeys.node(nodeId));
    }

    @Override
    public boolean isAlive(String nodeId) {
        return Boolean.TRUE.equals(redis.hasKey(ClusterKeys.node(nodeId)));
    }

    /**
     * 用 {@code SCAN} 而不是 {@code KEYS}：{@code KEYS} 是 O(N) 且会阻塞整个 Redis
     * （单线程），在几百万键的实例上足以造成一次可见的服务抖动。
     * 本方法只用于运维与诊断，但「只用于诊断」的代码同样会被复制到别处，
     * 所以从一开始就用不会伤到线上实例的那个。
     */
    @Override
    public List<NodeInfo> aliveNodes() {
        List<NodeInfo> nodes = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(ClusterKeys.nodePattern())
                .count(SCAN_COUNT)
                .build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                NodeInfo info = read(key);
                if (info != null) {
                    nodes.add(info);
                }
            }
        }
        return nodes;
    }

    /** 读一个节点键；值坏了（人为改过、旧版本写的格式）只跳过，不让诊断接口整批失败。 */
    private NodeInfo read(String key) {
        String raw = redis.opsForValue().get(key);
        if (raw == null) {
            return null;    // 扫描到与读之间过期了 —— 正常现象
        }
        try {
            return Json.read(raw, NodeInfo.class);
        } catch (RuntimeException e) {
            log.warn("节点键的值无法解析 key={} value={}：跳过。cause={}", key, raw, e.toString());
            return null;
        }
    }
}
