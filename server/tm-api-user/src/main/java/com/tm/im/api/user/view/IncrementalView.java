package com.tm.im.api.user.view;

import java.util.List;

/**
 * 增量拉取（03-rest-api.md §4.7）。
 *
 * <p>{@code latestSeq} 是服务端<b>当前</b>的最新序号，而不是这一页最后一条的：
 * 客户端在 {@code hasMore=true} 时要用它知道「还差多少」（以及决定要不要继续拉），
 * 而这一页的最后一条只是「还差多少」的下界。它也可能大于本页内容——
 * 那次拉取之后又来了新消息，这正是客户端下一轮要拉的东西。
 */
public record IncrementalView(List<MessageView> items, long latestSeq, boolean hasMore) {
}
