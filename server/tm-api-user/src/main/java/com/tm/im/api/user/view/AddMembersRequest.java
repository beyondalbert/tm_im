package com.tm.im.api.user.view;

import java.util.List;

/**
 * 加人请求体（03-rest-api.md §4.9 的 {@code POST /v1/conversations/{conv_id}/members}）。
 *
 * <p>与人有关的字段一律走「{@code @handle} 或纯数字 actor_id」两种写法（§1.7），
 * 解析在 {@code ActorLookup} 里，所以这里收到的就是一批原样的字符串。
 *
 * <p>缺字段（{@code null}）与空数组是两种错：前者回 <b>40001</b>（没传参数），
 * 后者回 <b>40001</b> 也是同一处抛的——因为「加 0 个人」没有意义，
 * 而把它当成成功会让客户端的一个空选择看起来像加成功了。
 */
public record AddMembersRequest(List<String> members) {
}
