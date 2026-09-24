package com.tm.im.channel.registry;

import com.tm.im.channel.codec.Frames;
import com.tm.im.channel.session.ConnectionRegistry;
import com.tm.im.channel.session.TmConnection;
import com.tm.im.proto.transport.KickReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 本节点连接注册表（DESIGN §7.4 的本地部分）。
 *
 * <p>实现要点都在「并发下的正确性」，而不是「用哪个 Map」：
 *
 * <ol>
 *   <li><b>顶号时不能误删新连接</b>。旧连接离线会触发 {@code channelInactive} →
 *       {@code unregister}。如果 unregister 是无条件 {@code remove(actorId)}，
 *       而此刻该 actorId 已经指向<b>新</b>连接，就会把新连接从表里摘掉——
 *       表现为「顶号之后谁也收不到推送」，且只在并发登录时出现。
 *       因此 remove 用 {@code remove(key, value)} 的原子「值相等才删」语义。</li>
 *   <li><b>注销必须幂等</b>。通道异常关闭、超时关闭、顶号关闭可能都在同一条连接上
 *       触发一次清理。</li>
 *   <li><b>不在 IO 线程做逐连接写之外的事</b>。这里所有方法都是 O(1) 的 Map 操作
 *       加一次 {@code writeAndFlush}，没有 DB、没有 Redis ——
 *       跨节点投递（Redis Pub/Sub）会由上层实现，不在此处。</li>
 * </ol>
 */
@Component
public class LocalConnectionRegistry implements ConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(LocalConnectionRegistry.class);

    /** key 是 actorId（一个 Actor 在本节点一条连接，见接口注释）。 */
    private final Map<Long, TmConnection> byActor = new ConcurrentHashMap<>();

    private final LongAdder dropped = new LongAdder();

    @Override
    public int register(TmConnection connection) {
        TmConnection previous = byActor.put(connection.actorId(), connection);
        if (previous == null) {
            return 0;
        }
        if (previous.channel() == connection.channel()) {
            // 同一条连接重复注册（例如客户端重发 AUTH 后又被放行），不算顶号。
            return 0;
        }
        log.info("同账号新连接顶掉旧连接 actorId={} old={} new={}",
                connection.actorId(), previous.remoteAddress(), connection.remoteAddress());
        // 老连接写一帧 KICK 再关。writeAndFlush 之后立刻 close 通常仍能刷出去，
        // 但顺序必须是「先写后关」——反过来的话客户端只会看到连接断开，
        // 不知道是「被顶号」还是「网络抖动」，于是会一直重连（02-auth.md §5.4 的语义）。
        previous.tryWrite(Frames.kick(KickReason.KICK_REASON_OTHER_DEVICE,
                "signed in from another device", 0));
        previous.channel().close();
        return 1;
    }

    @Override
    public void unregister(TmConnection connection) {
        // remove(key, value)：只有当前登记的就是这条连接时才移除。
        // 顶号场景下表里已经是新连接，这次删除会正确地什么都不做。
        byActor.remove(connection.actorId(), connection);
    }

    @Override
    public int push(long actorId, com.tm.im.proto.transport.Frame frame) {
        TmConnection connection = byActor.get(actorId);
        if (connection == null) {
            return 0;
        }
        if (connection.tryWrite(frame)) {
            return 1;
        }
        dropped.increment();
        log.warn("背压丢弃可丢帧 actorId={} cmd={} (累计 {} 帧) —— 该连接已超写缓冲水位，"
                        + "客户端重连后应按 last_seq 走 SYNC 补齐",
                actorId, frame.getCmd(), dropped.sum());
        return 0;
    }

    @Override
    public void broadcast(com.tm.im.proto.transport.Frame frame) {
        for (TmConnection connection : List.copyOf(byActor.values())) {
            if (!connection.tryWrite(frame)) {
                dropped.increment();
            }
        }
    }

    @Override
    public boolean isOnline(long actorId) {
        TmConnection c = byActor.get(actorId);
        return c != null && c.channel().isActive();
    }

    @Override
    public int size() {
        return byActor.size();
    }

    @Override
    public Collection<TmConnection> connections() {
        // copyOf：调用方拿到的是快照。直接返回 values() 会让调用方在
        // 遍历时被并发修改（注册/注销随时发生），表现为随机的
        // ConcurrentModificationException 或漏推。
        return List.copyOf(byActor.values());
    }

    @Override
    public long droppedFrames() {
        return dropped.sum();
    }
}
