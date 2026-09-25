package com.tm.im.channel.cluster;

/**
 * 一个存活节点在 {@code tm:node:{nodeId}} 上留下的自述（JSON）。
 *
 * <p>存的不只是「存在」这一个事实：节点号本身不足以让人定位问题。
 * 运维看到「路由指向 node-7」时，下一步必然是「node-7 是哪台机器、哪个端口」——
 * 把 host 与 nettyPort 一起写进去，这个问题就不需要去翻日志。
 *
 * <p>{@code startedAtMs} 用于判断「这个节点是不是刚重启过」：重启用的是同一个 nodeId，
 * 于是重启前的老路由在它重新注册后会<b>自动重新生效</b>（这是刻意的，见
 * {@link RedisActorRouteTable}），而最近重启过这一点只有时间戳能告诉排查的人。
 */
public record NodeInfo(String nodeId, String host, int nettyPort, long startedAtMs) {
}
