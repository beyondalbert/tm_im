package com.tm.im.api.admin.view;

/** Agent 扩展信息的后台视图（人没有这一项，字段为 null）。 */
public record AgentProfileView(String endpointUrl, int pushMode, String capabilities,
                               String modelInfo, Integer rateLimit) {
}
