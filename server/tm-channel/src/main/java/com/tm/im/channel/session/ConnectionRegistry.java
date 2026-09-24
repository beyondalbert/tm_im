package com.tm.im.channel.session;

import com.tm.im.proto.transport.Frame;

import java.util.Collection;

/**
 * 连接注册表（本节点视图）。
 *
 * <p>DESIGN §7.4 的本地部分：{@code ConcurrentHashMap<actorId, Channel>}。
 * 全局部分（Redis 的 {@code tm:route:{actorId}} 与跨节点 Pub/Sub）是后续实现，
 * 但接口先按「本地 + 可扩展」设计，避免 M3 时被迫改调用方。
 *
 * <p><b>一个 Actor 在本节点只保留一条连接</b>（新连接顶掉旧连接，
 * 旧连接收到 {@code KICK_REASON_OTHER_DEVICE}）。这是 DESIGN §7.4 明确的结构，
 * 也解释了为什么 {@code KickNotice} 里会有「其他端登录顶号」这个原因码。
 * 多设备同时在线需要改成 {@code Map<actorId, Set<Channel>>}，
 * 那是一个产品决策（要不要允许同一账号多端同时收消息），不是实现细节。
 */
public interface ConnectionRegistry {

    /**
     * 注册一条已鉴权连接。
     *
     * @return 被顶掉的旧连接数（0 或 1）。返回给调用方是为了日志里能记下
     *         「这个账号在别处登录了」，而不是让顶号静默发生
     */
    int register(TmConnection connection);

    /** 注销。实现必须幂等：重复注销（连接已因顶号被移除）不能报错。 */
    void unregister(TmConnection connection);

    /**
     * 把帧投递给某 Actor 在本节点的连接。
     *
     * @return 实际写入的连接数：0 表示不在本节点（离线或落在别的节点）
     * @implNote 连接写缓冲高水位以上（{@code isWritable()==false}）时，
     *           可丢帧（见 {@code Frames.droppableUnderBackpressure}）会被丢弃并计数，
     *           返回 0。这是 DESIGN §7.6「慢客户端不能拖垮网关」的落点。
     */
    int push(long actorId, Frame frame);

    /** 广播（优雅停机发 KICK 用）。 */
    void broadcast(Frame frame);

    /** 该 Actor 是否在本节点。 */
    boolean isOnline(long actorId);

    /** 本节点连接数。 */
    int size();

    /** 当前所有连接（只读视图，用于运维与测试断言）。 */
    Collection<TmConnection> connections();

    /** 因背压被丢弃的帧数（累计）。持续增长说明有慢客户端，是容量告警信号。 */
    long droppedFrames();
}
