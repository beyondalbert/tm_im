package com.tm.im.api.user.view;

/**
 * §3.5 删好友的响应。
 *
 * <p>{@code removed=false} 表示「本来就不是好友」——那<b>不是错误</b>
 * （客户端的目标状态「我们不再是好友」已经成立，见 {@code FriendService.removeFriend}），
 * 但它值得出现在响应里：排障时「我删了他，为什么他还能发消息」的答案之一是
 * 「你们本来就没有关系，那条消息走的是别的路径」。
 *
 * <p>副作用（§3.5）：单聊里双方此后发消息得到 {@code 40003}，
 * 而历史消息仍可见——所以这里没有 {@code conv_id}，这个接口不动会话。
 */
public record FriendRemovedView(long actorId, long targetId, boolean removed) {
}
