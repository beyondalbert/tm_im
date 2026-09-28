package com.tm.im.api.admin.view;

import java.time.Instant;

/**
 * 审计行视图（{@code admin_name} 是快照，见 DDL 注释）。
 *
 * <p>{@code detail} 原样透出 JSON 文本：它是排查的入口，而解析成对象只会
 * 把「某个动作多了一个字段」变成一次接口变更。
 */
public record AuditLogView(long id, long adminId, String adminName, String action,
                           String targetType, Long targetId, String detail, String ip,
                           Instant createdAt) {
}
