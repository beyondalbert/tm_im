package com.tm.im.api.user.view;

import java.time.Instant;

/**
 * 建群的结果（03-rest-api.md §4.2）。
 *
 * <p>不回成员列表：建群请求里就有成员，而客户端需要的那一份（带头像/昵称）应当去
 * {@code GET /v1/conversations/{id}} 取——那里的成员是<b>服务端眼里的</b>成员
 * （可能已被别人改动），比「我刚传上去的东西」更适合用来渲染。
 */
public record GroupConversationView(long convId,
                                    int convType,
                                    String title,
                                    long ownerActor,
                                    long memberCount,
                                    Instant createdAt) {
}
