package com.tm.im.api.user.view;

import java.time.Instant;

/**
 * 会话列表的一项（03-rest-api.md §4.3）。
 *
 * <p>{@code unreadCount} 由服务端算好（= 会话最新 {@code seq} − 我的已读游标）：
 * 让客户端自己减看似更省一次计算，但那样「未读数」就有两处定义——
 * 而两处的差异会以「红点和消息列表对不上」的形式出现，用户只会觉得「这个 App 有毛病」。
 *
 * <p>{@code peer} 只有单聊有，{@code title}/{@code memberCount} 主要给群聊用；
 * 单聊也会带 {@code memberCount}（恒为 2），这样客户端渲染列表时不必分支。
 *
 * <p>{@code updatedAt} 是<b>最近活跃时间</b>（最后一条消息的时间，没有消息时是会话创建时间），
 * 不是数据库里的行更新时间——列表就是按它排序的，客户端若要自己重排必须用同一个值。
 */
public record ConversationView(long convId,
                               int convType,
                               String title,
                               PeerView peer,
                               long lastSeq,
                               long lastReadSeq,
                               long unreadCount,
                               MessageView lastMessage,
                               boolean muted,
                               long memberCount,
                               Instant updatedAt) {
}
