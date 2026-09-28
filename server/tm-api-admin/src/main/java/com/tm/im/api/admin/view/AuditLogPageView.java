package com.tm.im.api.admin.view;

import java.util.List;

/**
 * 后台分页响应。字段名与用户端一致（{@code items} / {@code next_cursor} / {@code has_more}）：
 * 后台前端与用户端前端很可能共用一套分页组件，而「字段名差一个下划线」这种差异
 * 只在某个页面上表现成「翻页按钮永远不可点」。
 */
public record AuditLogPageView(List<AuditLogView> items, String nextCursor, boolean hasMore) {
}
