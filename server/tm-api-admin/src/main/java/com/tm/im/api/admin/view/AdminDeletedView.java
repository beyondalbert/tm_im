package com.tm.im.api.admin.view;

/**
 * 删除类动作的响应。
 *
 * <p>回显 {@code target_type}/{@code target_id} 而不是空对象：后台的操作是
 * 「批量看、逐条点」，而客户端需要一个能把响应与请求对上号的凭据
 * （乱序返回时尤其如此）。这两个字段正是审计行里的那两个。
 */
public record AdminDeletedView(String targetType, long targetId, boolean deleted) {
}
