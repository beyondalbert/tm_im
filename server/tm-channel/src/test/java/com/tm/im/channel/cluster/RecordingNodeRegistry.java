package com.tm.im.channel.cluster;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@link NodeRegistry} 的测试替身：记录注册／续期／注销的调用与参数。 */
public class RecordingNodeRegistry implements NodeRegistry {

    private final Map<String, NodeInfo> alive = new LinkedHashMap<>();
    private final List<NodeInfo> registrations = new ArrayList<>();
    private final List<Duration> registrationTtls = new ArrayList<>();

    private int renewCalls;
    private int unregisterCalls;

    private volatile boolean renewResult = true;
    private volatile boolean failRenew;
    private volatile boolean failRegister;

    @Override
    public void register(NodeInfo info, Duration ttl) {
        if (failRegister) {
            throw new IllegalStateException("模拟 Redis 不可用");
        }
        registrations.add(info);
        registrationTtls.add(ttl);
        alive.put(info.nodeId(), info);
    }

    @Override
    public boolean renew(String nodeId, Duration ttl) {
        renewCalls++;
        if (failRenew) {
            throw new IllegalStateException("模拟 Redis 不可用");
        }
        if (!renewResult) {
            alive.remove(nodeId);
        }
        return renewResult;
    }

    @Override
    public void unregister(String nodeId) {
        unregisterCalls++;
        alive.remove(nodeId);
    }

    @Override
    public boolean isAlive(String nodeId) {
        return alive.containsKey(nodeId);
    }

    @Override
    public List<NodeInfo> aliveNodes() {
        return List.copyOf(alive.values());
    }

    public void renewFails() {
        renewResult = false;
    }

    public void throwOnRenew() {
        failRenew = true;
    }

    public void throwOnRegister() {
        failRegister = true;
    }

    public List<NodeInfo> registrations() {
        return List.copyOf(registrations);
    }

    public List<Duration> registrationTtls() {
        return List.copyOf(registrationTtls);
    }

    public int renewCalls() {
        return renewCalls;
    }

    public int unregisterCalls() {
        return unregisterCalls;
    }
}
