package com.tm.im.api.user.view;

import java.util.List;

/**
 * {@code POST /v1/conversations/group} 的请求体（03-rest-api.md §4.2）。
 *
 * <p>没有 {@code notice}（群公告）字段：文档里有，但库里没有对应的列
 * （{@code conversation} 只有 {@code title}）。等真要做公告时它应该是
 * 一个可改的字段（{@code PATCH /v1/conversations/{id}}）而不是建群时的一次性输入——
 * 一次性输入的公告，第一次改的时候就要新加一个接口。文档已同步删掉它。
 *
 * <p>{@code members} 里含自己不会报错（会被忽略，群主那一行由服务端生成）：
 * 客户端把「我勾选的联系人」直接传上来是更自然的实现，为此回一个错误只会让它多写一段过滤代码。
 */
public record GroupConversationRequest(String title, List<String> members) {
}
