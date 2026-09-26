package com.tm.im.api.user.view;

import java.util.List;

/**
 * 消息分页（03-rest-api.md §4.6）。
 *
 * <p>{@code hasMore} 是精确值（服务端多取一行得出，见 DESIGN §10.2），
 * 所以客户端不必用「这一页是不是满的」去猜——那是猜不准的，而猜错的后果是
 * 少拉一页历史或多拉一次空页。
 */
public record MessagePageView(List<MessageView> items, String nextCursor, boolean hasMore) {
}
