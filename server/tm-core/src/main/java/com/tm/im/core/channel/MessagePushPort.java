package com.tm.im.core.channel;

import com.tm.im.domain.entity.Message;

/**
 * 推送网关（端口）—— 由 {@code tm-channel} 实现，领域服务只依赖这个接口。
 *
 * <p><b>为什么端口在 tm-core 而不在 tm-channel</b>：模块依赖方向是
 * {@code tm-core ← tm-channel}（DESIGN §12）。M3 的 MessageService 在 tm-core 里，
 * 它写完库之后要把消息推给对方——如果接口定义在 tm-channel，
 * tm-core 就必须反向依赖，架构上立刻破功（而「同构协议」正是靠这条单向依赖强制的）。
 *
 * <p><b>为什么参数是领域 {@link Message} 而不是 protobuf 帧</b>：
 * 传输帧类型由 {@code proto/transport.proto} 生成，编译在 tm-channel 里；
 * 让 tm-core 依赖它，等于把「线格式」渗进领域层，将来改协议（加字段、
 * 换载体）就会牵动业务代码。转换只发生在 tm-channel 一侧。
 *
 * <p><b>实现约定</b>：本节点没有该 actor 的连接时返回 0 而不是抛异常——
 * 「对方离线」是正常情况而非错误。跨节点投递（Redis 路由 + Pub/Sub）
 * 属于后续实现，调用方不应依赖「返回值 &gt; 0 才算成功」。
 */
public interface MessagePushPort {

    /**
     * 把一条消息推给某个 Actor 在本节点的连接。
     *
     * @return 实际写入的连接数：0 表示该 Actor 不在本节点（或已离线）
     */
    int pushToActor(long actorId, Message message);

    /** 该 Actor 是否有连接落在本节点。用于 REST 侧「是否值得起推送」的短路判断。 */
    boolean isOnline(long actorId);

    /** 本节点当前在线连接数（运维/压测用）。 */
    int localConnectionCount();
}
