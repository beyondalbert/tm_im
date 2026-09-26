package com.tm.im.api.user.view;

/**
 * {@code POST /v1/conversations/direct} 的请求体（03-rest-api.md §4.1）。
 *
 * <p>{@code peer} 的两种写法（{@code "@alice"} 与 {@code "1001"}）由
 * {@code ActorLookup} 统一解析，规则与报错见那里：数字与 handle 都能表示同一个人，
 * 所以「不带 @ 的字符串」被拒绝而不是被猜。
 */
public record DirectConversationRequest(String peer) {
}
