package com.tm.im.api.user.view;

/**
 * 改群名的响应（03-rest-api.md §4.9 的 {@code PATCH /v1/conversations/{conv_id}}）。
 *
 * <p>只回 {@code conv_id} 与新群名，不回整个会话详情：这个接口改的就是一个字段，
 * 回一份详情会让客户端以为「详情里的其他字段也刚刚变过」——而它们没有，
 * 用这份响应去覆盖本地缓存会把别处刚更新的未读数/最后一条消息一起改回旧的。
 *
 * <p>群公告（{@code notice}）不在这里：{@code conversation} 表没有那一列，
 * 文档里的这个字段已被删掉（见 README 的「已知不一致」）。
 */
public record ConversationTitleView(long convId, String title) {
}
