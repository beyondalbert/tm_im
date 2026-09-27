package com.tm.im.api.user.view;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * §6.2 信息流里的一项（§6.3「某人的动态」复用同一形状）。
 *
 * <p>{@code isFriendAuthor} 是<b>服务端算好的</b>，客户端不要自己判断
 * （文档 §6.2 的注释明确要求客户端不要重排）：判断依据是「调用者与作者的关系」，
 * 而客户端手上的好友列表可能过期。
 */
public record FeedItemView(long postId, PlazaAuthorView author, JsonNode content, int visibility,
                           boolean isFriendAuthor, Instant createdAt, int likeCount,
                           int commentCount, boolean likedByMe) {
}
