package com.tm.im.channel.cluster;

import java.util.Optional;

/**
 * Actor → 节点 的全局路由表（DESIGN §7.4 的「全局」那一半，Redis {@code tm:route:{actorId}}）。
 *
 * <p>本地表（{@code ConnectionRegistry}）回答「这个 Actor 在不在这台机器上」，
 * 本表回答「他在哪台机器上」。两者都会被用来决定推送怎么走，
 * 但它们的失效模式完全不同：本地表是内存，进程没了就没了；本表在 Redis 里，
 * <b>进程没了它还在</b>。所以本接口的语义必须能把「持有者已死」这件事表达出来 ——
 * 这正是 {@link #locate(long)} 返回空而不是返回一个陈旧 nodeId 的原因。
 *
 * <p><b>为什么是「写侧也能看出来」的接口</b>：绑定发生在鉴权成功之后（见
 * {@code ClusterAwareConnectionRegistry}），解绑发生在最后一条连接关闭之后。
 * 两者都不在 IO 线程上，但都在<b>登录与掉线的关键路径</b>上，因此实现必须容忍
 * Redis 抖动：宁可放弃跨节点投递（退化为对方重连时 SYNC 补齐），
 * 也不能让登录失败、或让一条正常关闭的连接卡住。
 */
public interface ActorRouteTable {

    /**
     * 把该 Actor 的路由指向本节点（覆盖旧值）。
     *
     * <p>覆盖是刻意的：同一个 Actor 在别处登录（换了节点），或在同节点重新连接，
     * 新的事实一定比旧的正确。旧节点的解绑会以 CAS 方式做（见 {@link #unbind(long)}），
     * 因此这次覆盖不会被旧连接的清理动作抹掉。
     */
    void bind(long actorId);

    /**
     * 释放路由：<b>仅当当前值仍指向本节点</b>时才删除。
     *
     * @return 是否真的删除了
     * @implNote 无条件删除是不行的：客户端换节点重连时，「新节点 bind」与
     *           「旧节点上那条连接的 channelInactive」之间没有顺序保证。
     *           若旧节点这时无条件删，就会把新节点的绑定删掉 ——
     *           结果是这个 Actor 在两侧都查不到路由：他明明在线，却收不到任何推送，
     *           直到下一次重连。这是一个只在「换节点重连」时出现的窗口，
     *           而且删掉的是一个「刚被写入」的键，排查时几乎不会怀疑到这里。
     */
    boolean unbind(long actorId);

    /**
     * 查询持有该 Actor 连接的节点。
     *
     * @return 空表示「没有任何节点持有它」：要么确实离线，要么记录指向的节点已死。
     *         调用方对这两种情况的处置相同 —— 消息已经落库，等对方上线后同步
     */
    Optional<String> locate(long actorId);
}
