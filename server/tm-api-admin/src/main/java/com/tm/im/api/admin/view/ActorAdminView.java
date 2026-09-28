package com.tm.im.api.admin.view;

import java.time.Instant;

/**
 * 参与者的后台视图：比用户端的 {@code ActorView} 多两样东西 ——
 * {@code status}（封禁状态是后台最关心的字段）与 {@code agentProfile}（Agent 的管理面）。
 */
public record ActorAdminView(long actorId, int actorType, String handle, String displayName,
                             String avatarUrl, String bio, int status, Instant createdAt,
                             AgentProfileView agentProfile) {
}
