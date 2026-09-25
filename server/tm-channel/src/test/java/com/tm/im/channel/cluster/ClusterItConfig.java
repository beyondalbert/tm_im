package com.tm.im.channel.cluster;

import com.tm.im.storage.it.RedisItConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 集群路由集成测试的容器：<b>只要真实 Redis，不要数据库</b>。
 *
 * <p>路由与节点探活纯粹是 Redis 上的 STRING 操作，把 MySQL（以及 ShardingSphere）
 * 拉进来只会让「Redis 语义错了」与「数据库连不上」变成同一种红灯。
 */
@Configuration
@Import(RedisItConfig.class)
public class ClusterItConfig {
}
