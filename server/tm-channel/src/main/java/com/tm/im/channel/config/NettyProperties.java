package com.tm.im.channel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 长连接服务配置。键名与 {@code deploy/conf/application-external.yml.example}
 * 的 {@code tm.netty.*} 段落一一对应 —— 那份模板是运维视角的权威文档，
 * 这里的字段默认值必须与它保持一致。两处数值不同时以配置为准，
 * 但「默认值不一致」本身就是缺陷：它意味着不写配置的开发环境与生产行为不同。
 */
@ConfigurationProperties(prefix = "tm.netty")
public class NettyProperties {

    /** 关掉它可以在只需要 REST 的场景（如纯管理后台）不占端口。 */
    private boolean enabled = true;

    /**
     * 监听端口。WebSocket（{@code /ws}）与原生 TCP <b>共用一个端口</b>，
     * 靠首字节分流（{@code ProtocolSniffer}）。测试里设为 0 让系统分配空闲端口。
     */
    private int port = 8090;

    /** boss 只做 accept，1 个线程就够（accept 是极短的操作）。 */
    private int bossThreads = 1;

    /** worker 承担 IO 编解码；0 表示按 CPU 核数 × 2 计算。 */
    private int workerThreads = 0;

    /**
     * 业务线程池规模（DESIGN §7.3：绝不在 IO 线程跑 DB/Redis/HTTP）。
     * 这个池的大小直接决定「并发处理中的请求数」，与 worker 线程数是两回事。
     */
    private int businessThreads = 32;

    /** 业务队列容量。满了之后的策略见 {@code BusinessExecutor}。 */
    private int businessQueueCapacity = 20000;

    /**
     * 服务端主动探活间隔：读空闲这么久没有收到任何字节，就发一帧 PING。
     *
     * <p>它是<b>兜底</b>而不是主通道：客户端的正常行为是每
     * {@code heartbeat_sec}（AUTH_OK 里返回，默认 30s）主动发 PING。
     * 服务端这一侧的空闲探测用于发现「客户端的定时器已经不转了」这类半死连接。
     */
    private int heartbeatIdleSeconds = 30;

    /** 全空闲这么久直接断开（DESIGN §7.2 的 90s 阈值）。 */
    private int heartbeatTimeoutSeconds = 90;

    /**
     * 握手超时：连上之后多久没收到 AUTH 帧就断开（04-realtime.md §5.2 规定 5 秒）。
     *
     * <p>没有它，攻击者可以只建立连接不发数据，用极低的成本占满连接数。
     */
    private long authTimeoutMs = 5000;

    /**
     * 单帧字节上限。
     *
     * <p>三个作用域共用这一个值：原生 TCP 的长度前缀校验、
     * WebSocket 的 {@code maxFramePayloadLength}、以及协议分流器
     * （{@code ProtocolSniffer}）的判据。
     *
     * <p><b>必须小于 16MB（{@code 1 << 24}）</b>：分流规则是「长度前缀的首字节为 0x00
     * 则视为原生 TCP」，长度一旦达到 16MB，首字节就不再是 0x00，规则失效。
     * 该约束由 {@code NettyServer} 启动时校验，不是靠注释提醒。
     */
    private int maxFrameBytes = 1024 * 1024;

    /** 单连接写缓冲高水位：超过后 {@code isWritable()} 变 false，触发背压丢弃。 */
    private int writeBufferHighWaterMark = 64 * 1024;

    private int writeBufferLowWaterMark = 32 * 1024;

    /** 单连接每秒帧数上限（简易令牌桶，防刷）。超限发 42901 并断开。 */
    private int maxFramesPerSecond = 200;

    /**
     * 全局连接数上限。
     *
     * <p>注意这是「每实例」的软上限：真正的容量受文件描述符、内存、带宽制约。
     * 达到上限时新连接会被立即关闭，而不是排队 —— 排队会让新建连接超时，
     * 客户端看到的错误更模糊。
     */
    private int maxConnections = 200_000;

    /** 优雅停机时等待写缓冲刷出的上限（DESIGN §7.7 规定 5 秒）。 */
    private long gracefulShutdownMillis = 5000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getBossThreads() {
        return bossThreads;
    }

    public void setBossThreads(int bossThreads) {
        this.bossThreads = bossThreads;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    /** 折算后的 worker 线程数：0 → CPU × 2（DESIGN §7.1）。 */
    public int resolvedWorkerThreads() {
        return workerThreads > 0 ? workerThreads : Runtime.getRuntime().availableProcessors() * 2;
    }

    public int getBusinessThreads() {
        return businessThreads;
    }

    public void setBusinessThreads(int businessThreads) {
        this.businessThreads = businessThreads;
    }

    public int getBusinessQueueCapacity() {
        return businessQueueCapacity;
    }

    public void setBusinessQueueCapacity(int businessQueueCapacity) {
        this.businessQueueCapacity = businessQueueCapacity;
    }

    public int getHeartbeatIdleSeconds() {
        return heartbeatIdleSeconds;
    }

    public void setHeartbeatIdleSeconds(int heartbeatIdleSeconds) {
        this.heartbeatIdleSeconds = heartbeatIdleSeconds;
    }

    public int getHeartbeatTimeoutSeconds() {
        return heartbeatTimeoutSeconds;
    }

    public void setHeartbeatTimeoutSeconds(int heartbeatTimeoutSeconds) {
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
    }

    public long getAuthTimeoutMs() {
        return authTimeoutMs;
    }

    public void setAuthTimeoutMs(long authTimeoutMs) {
        this.authTimeoutMs = authTimeoutMs;
    }

    public int getMaxFrameBytes() {
        return maxFrameBytes;
    }

    public void setMaxFrameBytes(int maxFrameBytes) {
        this.maxFrameBytes = maxFrameBytes;
    }

    public int getWriteBufferHighWaterMark() {
        return writeBufferHighWaterMark;
    }

    public void setWriteBufferHighWaterMark(int writeBufferHighWaterMark) {
        this.writeBufferHighWaterMark = writeBufferHighWaterMark;
    }

    public int getWriteBufferLowWaterMark() {
        return writeBufferLowWaterMark;
    }

    public void setWriteBufferLowWaterMark(int writeBufferLowWaterMark) {
        this.writeBufferLowWaterMark = writeBufferLowWaterMark;
    }

    public int getMaxFramesPerSecond() {
        return maxFramesPerSecond;
    }

    public void setMaxFramesPerSecond(int maxFramesPerSecond) {
        this.maxFramesPerSecond = maxFramesPerSecond;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public long getGracefulShutdownMillis() {
        return gracefulShutdownMillis;
    }

    public void setGracefulShutdownMillis(long gracefulShutdownMillis) {
        this.gracefulShutdownMillis = gracefulShutdownMillis;
    }
}
