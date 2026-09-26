package com.tm.im.api.user.view;

/**
 * 获取或创建单聊会话的结果（03-rest-api.md §4.1）。
 *
 * <p>{@code created} 区分「新建」与「复用」：客户端靠它决定要不要把这个会话插到列表最前面
 * （复用时列表本来就有它，位置由最近活跃时间决定）。不带这个字段的话客户端只能猜，
 * 而猜错的后果是新会话不出现。
 */
public record DirectConversationView(long convId,
                                     int convType,
                                     PeerView peer,
                                     boolean created,
                                     long lastSeq) {
}
