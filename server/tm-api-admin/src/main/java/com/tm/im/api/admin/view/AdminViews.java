package com.tm.im.api.admin.view;

import com.tm.im.core.admin.AdminService;
import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.entity.AdminAuditLog;
import com.tm.im.domain.entity.AdminUser;
import com.tm.im.domain.entity.AgentProfile;
import com.tm.im.domain.entity.Post;

import java.time.ZoneId;
import java.util.List;

/**
 * 实体 → 视图的唯一转换点。
 *
 * <p>「唯一」是刻意的：两个接口各自把实体拼成响应时，同一个字段迟早会
 * 在一边被判空、在另一边抛 NPE，而那种差异只在特定数据（bio 为 null）下出现。
 */
public final class AdminViews {

    private AdminViews() {
    }

    /** 墙上时间 → 对外时间。控制器拿到的是 {@code LocalDateTime}，这里统一口径。 */
    public static java.time.Instant instant(java.time.LocalDateTime time, ZoneId zone) {
        return Timestamps.millis(time, zone);
    }

    public static AdminView admin(AdminUser admin, ZoneId zone) {
        return new AdminView(admin.getId(), admin.getUsername(), admin.getDisplayName(),
                admin.getRole() == null ? 0 : admin.getRole().code(),
                admin.getStatus() == null ? 0 : admin.getStatus().code(),
                Timestamps.millis(admin.getCreatedAt(), zone),
                Timestamps.millis(admin.getLastLoginAt(), zone));
    }

    public static ActorAdminView actor(AdminService.ActorDetail detail, ZoneId zone) {
        Actor actor = detail.actor();
        return new ActorAdminView(actor.getId(),
                actor.getActorType() == null ? 0 : actor.getActorType().code(),
                actor.getHandle(), actor.getDisplayName(), actor.getAvatarUrl(), actor.getBio(),
                actor.getStatus() == null ? 0 : actor.getStatus().code(),
                Timestamps.millis(actor.getCreatedAt(), zone),
                agentProfile(detail.agentProfile()));
    }

    public static ActorAdminView actor(Actor actor, ZoneId zone) {
        return actor(new AdminService.ActorDetail(actor, null), zone);
    }

    static AgentProfileView agentProfile(AgentProfile profile) {
        if (profile == null) {
            return null;
        }
        return new AgentProfileView(profile.getEndpointUrl(),
                profile.getPushMode() == null ? 0 : profile.getPushMode().code(),
                profile.getCapabilities(), profile.getModelInfo(), profile.getRateLimit());
    }

    public static PostAdminView post(Post post, ZoneId zone) {
        return new PostAdminView(post.getId(),
                post.getAuthorId() == null ? 0 : post.getAuthorId(),
                post.getContent(),
                post.getVisibility() == null ? 0 : post.getVisibility().code(),
                post.getLikeCount() == null ? 0 : post.getLikeCount(),
                post.getCommentCount() == null ? 0 : post.getCommentCount(),
                Timestamps.millis(post.getCreatedAt(), zone));
    }

    public static AuditLogView audit(AdminAuditLog row, ZoneId zone) {
        return new AuditLogView(row.getId(), row.getAdminId(), row.getAdminName(), row.getAction(),
                row.getTargetType(), row.getTargetId(), row.getDetail(), row.getIp(),
                Timestamps.millis(row.getCreatedAt(), zone));
    }

    public static List<ActorAdminView> actors(List<Actor> rows, ZoneId zone) {
        return rows.stream().map(a -> actor(a, zone)).toList();
    }

    public static List<PostAdminView> posts(List<Post> rows, ZoneId zone) {
        return rows.stream().map(p -> post(p, zone)).toList();
    }

    public static List<AuditLogView> audits(List<AdminAuditLog> rows, ZoneId zone) {
        return rows.stream().map(r -> audit(r, zone)).toList();
    }

    public static List<AdminView> admins(List<AdminUser> rows, ZoneId zone) {
        return rows.stream().map(a -> admin(a, zone)).toList();
    }
}
