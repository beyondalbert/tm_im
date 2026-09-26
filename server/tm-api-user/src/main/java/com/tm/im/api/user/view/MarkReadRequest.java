package com.tm.im.api.user.view;

/**
 * {@code POST /v1/conversations/{conv_id}/read} 的请求体（03-rest-api.md §4.8）。
 *
 * <p>{@code lastReadSeq} 是包装类型：字段缺失时回 40001（缺参数），
 * 而不是被当成 0——把「没传」当成「读到第 0 条」会让一次坏请求
 * 把未读数<b>清不掉也不报错</b>，客户端只能看到「红点还在」。
 */
public record MarkReadRequest(Long lastReadSeq) {
}
