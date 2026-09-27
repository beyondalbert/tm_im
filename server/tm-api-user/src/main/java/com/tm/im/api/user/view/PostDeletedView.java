package com.tm.im.api.user.view;

/**
 * §6.4 删除动态的响应。
 *
 * <p>{@code deleted} 恒为 true（不是作者就根本走不到这里，会先拿到 40302），
 * 留着它是为了与 {@code FriendRemovedView} 的 {@code removed} 保持同一形状：
 * 「删除类接口回一个布尔」是这个仓库的既有约定，客户端不必猜。
 */
public record PostDeletedView(long postId, boolean deleted) {
}
