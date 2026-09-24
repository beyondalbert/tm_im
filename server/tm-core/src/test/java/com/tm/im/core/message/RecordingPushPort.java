package com.tm.im.core.message;

import com.tm.im.core.channel.MessagePushPort;
import com.tm.im.domain.entity.Message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 推送口的测试替身：记录「推给谁、推了哪条」。
 *
 * <p>真实的推送实现（{@code LocalMessagePushPort}）要连着 Netty 的注册表，
 * 在集成测试里没必要把整条长连接链路拖进来——本测试要验证的是
 * 「扇出决策对不对」（推给谁、推几次、大群是否不推），
 * 而「帧能不能写进 Channel」由 tm-channel 的端到端测试负责。
 */
public class RecordingPushPort implements MessagePushPort {

    /** actorId -> 收到的 seq 列表（按到达顺序）。用 LinkedHashMap 让失败信息可读。 */
    private final Map<Long, List<Long>> delivered = new LinkedHashMap<>();

    /** 在线的 actor（不在集合里的返回 0，模拟「对方离线」）。 */
    private final List<Long> online = new ArrayList<>();

    public void setOnline(long... actorIds) {
        online.clear();
        for (long id : actorIds) {
            online.add(id);
        }
    }

    public void reset() {
        delivered.clear();
    }

    @Override
    public int pushToActor(long actorId, Message message) {
        if (!isOnline(actorId)) {
            return 0;
        }
        delivered.computeIfAbsent(actorId, k -> new ArrayList<>()).add(message.getSeq());
        return 1;
    }

    @Override
    public boolean isOnline(long actorId) {
        return online.contains(actorId);
    }

    @Override
    public int localConnectionCount() {
        return online.size();
    }

    /** 某个 actor 收到的 seq 列表；从未收到过返回空列表。 */
    public List<Long> seqsTo(long actorId) {
        return delivered.getOrDefault(actorId, List.of());
    }

    public int pushCount() {
        return delivered.values().stream().mapToInt(List::size).sum();
    }
}
