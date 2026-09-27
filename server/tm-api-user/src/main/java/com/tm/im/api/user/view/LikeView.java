package com.tm.im.api.user.view;

/**
 * §6.5 点赞/取消的响应。
 *
 * <p>返回计数与 {@code liked_by_me}（而不是一个空对象）：客户端据此就地改那个数字，
 * 不必为了刷新一个「赞」重拉整页信息流。字段名与 §6.2 的对应字段一致——
 * 两处名字不同会逼客户端写两套映射。
 */
public record LikeView(long postId, int likeCount, boolean likedByMe) {
}
