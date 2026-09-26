package com.tm.im.api.user.view;

import com.tm.im.core.agent.AgentService;
import com.tm.im.domain.entity.AgentProfile;
import com.tm.im.domain.entity.Actor;

import java.time.ZoneId;
import java.util.List;

/**
 * Agent 领域的对象 → 对外视图（03-rest-api.md §7）—— <b>唯一转换点</b>。
 *
 * <p>两件事在这里一次做完，别处不再重复：
 * <ul>
 *   <li>{@code capabilities}：库里存的是 JSON 数组文本，出参是数组。
 *       让每个接口自己解析等于把「解析失败怎么办」这个问题复制 N 遍；</li>
 *   <li>时间换算（{@code tm.time.zone} → {@code Instant}，截到毫秒），
 *       理由同 {@code ActorViews}。</li>
 * </ul>
 *
 * <p><b>凭据只在这里被拼一次</b>：{@link #created} 与 {@link #rotateKey} 是两个
 * 允许带上明文的出口，其余全部走 {@link #view}。这样「哪些响应里有明文」
 * 在代码里是可枚举的（三个方法名）。
 */
public final class AgentViews {

    private AgentViews() {
    }

    public static AgentView view(AgentService.AgentEntry entry, ZoneId zone) {
        Actor actor = entry.actor();
        AgentProfile profile = entry.profile();
        return new AgentView(
                actor.getId(),
                actor.getActorType() == null ? 0 : actor.getActorType().code(),
                actor.getHandle(),
                actor.getDisplayName(),
                actor.getAvatarUrl(),
                actor.getBio(),
                actor.getStatus() == null ? 0 : actor.getStatus().code(),
                profile.getPushMode() == null ? 0 : profile.getPushMode().code(),
                profile.getEndpointUrl(),
                capabilities(profile.getCapabilities()),
                profile.getModelInfo(),
                profile.getRateLimit() == null ? 0 : profile.getRateLimit(),
                profile.getOwnerActor() == null ? 0 : profile.getOwnerActor(),
                Timestamps.millis(actor.getCreatedAt(), zone));
    }

    public static List<AgentView> list(List<AgentService.AgentEntry> entries, ZoneId zone) {
        return entries.stream().map(entry -> view(entry, zone)).toList();
    }

    public static AgentCreatedView created(AgentService.Created created, ZoneId zone) {
        Actor actor = created.actor();
        AgentProfile profile = created.profile();
        return new AgentCreatedView(
                actor.getId(),
                actor.getHandle(),
                actor.getActorType() == null ? 0 : actor.getActorType().code(),
                actor.getDisplayName(),
                profile.getPushMode() == null ? 0 : profile.getPushMode().code(),
                profile.getEndpointUrl(),
                capabilities(profile.getCapabilities()),
                created.apiKey(),
                created.webhookSecret(),
                Timestamps.millis(actor.getCreatedAt(), zone));
    }

    public static AgentRotatedKeyView rotateKey(long actorId, String apiKey) {
        return new AgentRotatedKeyView(actorId, apiKey);
    }

    /**
     * 能力列表：JSON 数组文本 → {@code List<String>}。
     *
     * <p>解析失败返回空列表而不是抛：这一列是<b>展示元数据</b>，一个坏值（早期版本写过
     * 别的形状、或有人手工改过库）不该让整个 Agent 列表打不开。它能坏成什么样
     * 只取决于写入侧，而写入侧由 {@code AgentService.normalizeCapabilities} 保证。
     */
    private static List<String> capabilities(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return com.tm.im.common.json.Json.readList(json, String.class);
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
