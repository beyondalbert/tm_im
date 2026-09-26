package com.tm.im.api.user.view;

import java.time.Instant;

/**
 * §3.2 同意/拒绝好友请求的响应。
 *
 * <p>两处与文档示例的差别，都是刻意的：
 * <ul>
 *   <li><b>多了 {@code request_id}</b>：拒绝之后那一行就被删了（DESIGN §11.4），
 *       客户端需要知道「删掉的是哪一条」才能从本地列表里精确移除它；</li>
 *   <li><b>拒绝时 {@code status=0}、{@code conv_id=null}</b>：{@code 0} 不是一个真实的状态码，
 *       它的含义是「这段关系已不存在」（拒绝即删除）。同意时是 {@code 2=ACCEPTED}。</li>
 * </ul>
 *
 * <p>「同意」是幂等的（§3.5 的重试表把它列为可安全重试），所以重复调用返回的
 * {@code conv_id} 与第一次相同——那正是幂等该有的样子：重试拿到同一个结果，
 * 而不是一个「已经同意了」的错误。
 */
public record FriendDecisionView(long requestId,
                                 long actorA,
                                 long actorB,
                                 int status,
                                 Instant updatedAt,
                                 Long convId) {
}
