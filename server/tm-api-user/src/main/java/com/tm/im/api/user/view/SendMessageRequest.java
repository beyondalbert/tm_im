package com.tm.im.api.user.view;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code POST /v1/conversations/{conv_id}/messages} 的请求体（03-rest-api.md §4.5）。
 *
 * <p>{@code content} 用 {@link JsonNode} 而不是 {@code Map}：它要按 {@code msg_type}
 * 校验结构（TEXT 必须含 {@code text}、IMAGE 必须含 {@code media_id}），
 * 而那段校验的<b>唯一</b>实现在 {@code MessageService}——在这里先反序列化成一个 Map
 * 再拼回字符串，等于把「哪些结构合法」这件事抄了第二遍（漏一条就是一个静默放行的非法消息）。
 *
 * <p>{@code replyTo} 用包装类型：{@code null}（字段缺失）与 {@code 0}（显式给了 0）
 * 都是「不引用」，但只有前者是客户端「没写这个字段」。区分它们没有业务意义，
 * 所以两者都当作「不引用」——刻意用包装类型只是为了不把「缺失」变成一个静默的 0 值语义
 * （在别处它就是「引用了 id 为 0 的消息」）。
 */
public record SendMessageRequest(String msgType, String clientMsgId, JsonNode content, Long replyTo) {
}
