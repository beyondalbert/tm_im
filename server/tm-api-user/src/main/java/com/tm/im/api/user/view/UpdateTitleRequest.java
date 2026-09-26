package com.tm.im.api.user.view;

/**
 * 改群名请求体（03-rest-api.md §4.9 的 {@code PATCH /v1/conversations/{conv_id}}）。
 *
 * <p>{@code title} 为空或缺失回 <b>40001</b>（群名是必填的），超长回 <b>40002</b>。
 * 超长时<b>报错而不是截断</b>——与建群同一个理由：截断会让客户端回显的群名
 * 与库里的不同，而「回显不一致」是最难被当成 bug 报告的一类问题。
 */
public record UpdateTitleRequest(String title) {
}
