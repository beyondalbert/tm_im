package com.tm.im.api.user.view;

/**
 * 已读上报的结果（03-rest-api.md §4.8）。
 *
 * <p>{@code lastReadSeq} 是<b>生效后</b>的游标（可能大于请求值，因为游标只前进），
 * 客户端必须用它覆盖本地值：否则「上报 3（当前已是 7）」之后本地仍是 3，
 * 而服务端算出的 {@code unreadCount} 是 0——两边就此长期不一致。
 */
public record ReadView(long convId, long lastReadSeq, long unreadCount) {
}
