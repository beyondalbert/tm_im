package com.tm.im.api.user.view;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 发消息的回执（03-rest-api.md §4.5）。
 *
 * <p>比 {@link MessageView} 多一个 {@code client_msg_id}：它是<b>发送方</b>的幂等键，
 * 回执里带着它，客户端才能把「本地已经渲染出来的那条」与「服务端刚确认的这条」对上
 * （弱网下客户端往往先本地回显，收到回执才知道权威的 {@code seq}）。
 *
 * <p>所有字段都取自<b>落库后的那一行</b>，而不是请求里的值。这样「幂等重放返回完全相同的响应」
 * 是结构上成立的（同一行映射出来的字节必然相同），而不是靠两处代码分别拼出来的巧合——
 * 后者一旦有一处漏了个字段（比如用了请求里的 {@code content} 而不是库里的），
 * 重放就会有细微差异，而客户端会据此认为「这是两条不同的消息」。
 */
public record SendResultView(long messageId,
                             long convId,
                             long seq,
                             String clientMsgId,
                             long senderId,
                             String msgType,
                             JsonNode content,
                             Long replyTo,
                             Instant createdAt) {
}
