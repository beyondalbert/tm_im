package com.tm.im.api.user.view;

import java.util.List;

/**
 * 会话列表分页（03-rest-api.md §4.3 / §1.5）。
 *
 * <p>与 {@link MessagePageView} 结构相同也刻意分成两个类型：它们的 {@code items} 不同，
 * 而「共用一个泛型 PageView」会让某天给消息加分页字段时顺手把会话列表也改了
 * （编译不会报错，客户端会收到一个它不认识的响应）。
 */
public record ConversationPageView(List<ConversationView> items, String nextCursor, boolean hasMore) {
}
