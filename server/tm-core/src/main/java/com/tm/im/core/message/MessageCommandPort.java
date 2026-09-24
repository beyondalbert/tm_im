package com.tm.im.core.message;

/**
 * 消息命令的调用面 —— 长连接（tm-channel）与 REST 都用它。
 *
 * <p><b>为什么要有这层接口</b>：{@link MessageService} 的构造依赖七个东西
 * （四个仓储、推送口、ID 生成器、配置、时区）。让 tm-channel 的处理器直接依赖它，
 * 会让「只想验证帧怎么编解码」的测试不得不先搭一套存储——而 tm-channel 的测试
 * 里既没有数据库也不需要数据库。
 *
 * <p>有了它，两个层次各自用最合适的验证手段：
 * <ul>
 *   <li>tm-channel 的端到端测试用内存实现，验证「帧 ↔ 调用」的翻译是否正确
 *       （ACK 字段、错误码、req_id 回传、跨连接的 PUSH）；</li>
 *   <li>tm-core 的集成测试用真实 MySQL + Redis，验证写路径本身的正确性
 *       （幂等、序号、自愈、扇出）。</li>
 * </ul>
 * 两者都不需要假装对方那半段的实现，因此各自的失败信息都指向真正出错的地方。
 *
 * <p>它<b>不是</b>「为了将来可能换实现」的抽象：实现只有 {@link MessageService} 一个，
 * 而且不该有第二个——错误码与准入规则必须只有一处定义。
 */
public interface MessageCommandPort {

    /** 发送消息。语义见 {@link MessageService#send}。 */
    MessageService.SendOutcome send(MessageService.SendCommand cmd);

    /** 已读上报。语义见 {@link MessageService#markRead}。 */
    long markRead(long convId, long actorId, long lastReadSeq);

    /**
     * 断点续传拉取（{@code CMD_SYNC}）。语义见 {@link MessageService#sync}。
     *
     * <p>它在这层接口里的意义与其他两个方法不同：{@code sync} 是唯一一个
     * <b>把存储里的数据读回来发给客户端</b>的命令，因此它的替身实现（tm-channel 的内存版）
     * 必然会“看起来像”真的仓储——而它到底有没有正确算 {@code has_more}、
     * 该不该跳过非成员会话，那些判断在 {@link MessageService} 里（由 tm-core 的测试验证）。
     * 上层只负责「把这个结果翻译成一帧 SYNC_RESP（必要时再跟一帧 SYNC_END）」。
     */
    MessageService.SyncOutcome sync(MessageService.SyncCommand cmd);
}
