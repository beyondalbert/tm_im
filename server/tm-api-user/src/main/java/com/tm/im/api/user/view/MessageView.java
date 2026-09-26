package com.tm.im.api.user.view;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 一条消息的对外表示（03-rest-api.md §4.6 / §4.7 / §4.3 的 {@code last_message}）。
 *
 * <p>{@code msgType} 是<b>名字</b>（{@code "TEXT"}）而不是库里的数字编码，
 * 与 §4.5 的请求体一致：客户端发的是 {@code "TEXT"}，收回来的也是 {@code "TEXT"}，
 * 两边对称。会话类型/角色/账号状态那些<b>只出现在响应里</b>的枚举才用数字
 * （与库编码一致、与长连接帧一致）。
 *
 * <p>{@code content} 是<b>解析好的 JSON 对象</b>而不是字符串：库里存的是 JSON 文本，
 * 直接透出去会让客户端拿到一个「长得像对象的字符串」，于是每个客户端都要自己再解一次——
 * 而其中一个会用错（比如对字符串做 {@code content.text} 得到 undefined）。
 * 解析失败的行只记 WARN 并给一个空对象（见 {@link MessageViews}）：一条坏行不该让整页拉不开。
 */
public record MessageView(long messageId,
                          long convId,
                          long seq,
                          long senderId,
                          String msgType,
                          JsonNode content,
                          Long replyTo,
                          Instant createdAt) {
}
