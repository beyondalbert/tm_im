package com.tm.im.api.user.view;

import com.fasterxml.jackson.databind.JsonNode;
import com.tm.im.common.json.Json;
import com.tm.im.core.plaza.PlazaService;
import com.tm.im.domain.entity.Post;
import com.tm.im.domain.entity.PostComment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 广场领域对象 → 对外视图（03-rest-api.md §6）—— <b>唯一转换点</b>。
 *
 * <p>与 {@code MessageViews} 同职责划分，且同样在这里做三件容易被分散、然后漂移的事：
 * <ol>
 *   <li><b>时区换算</b>（{@code tm.time.zone} → {@code Instant}）；</li>
 *   <li><b>枚举用数字</b>：{@code visibility} 对外是 {@code 1/2}（§6.1 的示例就是数字），
 *       与消息的 {@code msg_type} 用名字不同——这处差异是文档定的，不是笔误；</li>
 *   <li><b>content 的解析</b>：库里是 JSON 文本，对外必须是对象。</li>
 * </ol>
 *
 * <p>content 解析失败时给一个空对象并记 WARN（与 {@code MessageViews.content} 同一取舍）：
 * 库里是 {@code NOT NULL} 的 JSON 列，解不出来只可能是有人绕过应用改了库，
 * 而让整页信息流打不开比显示一张空卡片糟糕得多。
 */
public final class PlazaViews {

    private static final Logger log = LoggerFactory.getLogger(PlazaViews.class);

    /** 枚举列缺失时的对外值。与 {@code ActorViews} 对 null 枚举的处理一致（0 = 未知）。 */
    private static final int UNKNOWN = 0;

    private PlazaViews() {
    }

    public static PostView toView(Post post, ZoneId zone) {
        return new PostView(
                post.getId() == null ? 0L : post.getId(),
                post.getAuthorId() == null ? 0L : post.getAuthorId(),
                content(post),
                post.getVisibility() == null ? UNKNOWN : post.getVisibility().code(),
                instant(post.getCreatedAt(), zone));
    }

    public static List<FeedItemView> feed(List<PlazaService.FeedEntry> items, ZoneId zone) {
        List<FeedItemView> out = new ArrayList<>(items.size());
        for (PlazaService.FeedEntry entry : items) {
            out.add(new FeedItemView(
                    entry.post().getId(),
                    PlazaAuthorView.of(entry.author()),
                    content(entry.post()),
                    entry.post().getVisibility() == null ? UNKNOWN : entry.post().getVisibility().code(),
                    entry.friendAuthor(),
                    instant(entry.post().getCreatedAt(), zone),
                    count(entry.post().getLikeCount()),
                    count(entry.post().getCommentCount()),
                    entry.likedByMe()));
        }
        return out;
    }

    public static List<CommentView> comments(List<PlazaService.CommentEntry> items, ZoneId zone) {
        List<CommentView> out = new ArrayList<>(items.size());
        for (PlazaService.CommentEntry entry : items) {
            PostComment comment = entry.comment();
            if (entry.author() == null) {
                // 作者不存在（账号被删）：与 PlazaService 对动态作者的处理不同——
                // 评论缺作者仍然可以展示（正文与时间都在），所以这里不跳过，
                // 由视图层给出一个「匿名」作者，避免整条讨论线因为一个人退网而消失。
                log.warn("评论的作者不存在 commentId={} authorId={}",
                        comment.getId(), comment.getAuthorId());
            }
            out.add(new CommentView(
                    comment.getId(),
                    comment.getPostId(),
                    entry.author() == null
                            ? new PlazaAuthorView(comment.getAuthorId() == null ? 0L : comment.getAuthorId(),
                                    0, null, null, null)
                            : PlazaAuthorView.of(entry.author()),
                    comment.getContent(),
                    comment.getReplyToCommentId(),
                    instant(comment.getCreatedAt(), zone)));
        }
        return out;
    }

    public static LikeView like(PlazaService.LikeOutcome outcome) {
        return new LikeView(outcome.postId(), outcome.likeCount(), outcome.liked());
    }

    /** §6.6 单条评论（发评论的响应）。与 {@link #comments} 共用同一条渲染路径。 */
    public static CommentView toComment(PlazaService.CommentEntry entry, ZoneId zone) {
        return comments(List.of(entry), zone).get(0);
    }

    // ------------------------------------------------------------------ 内部

    private static JsonNode content(Post post) {
        String raw = post.getContent();
        if (raw == null || raw.isBlank()) {
            log.warn("动态 content 为空（库里是 NOT NULL，这不该发生）postId={}", post.getId());
            return Json.mapper().createObjectNode();
        }
        try {
            JsonNode node = Json.mapper().readTree(raw);
            if (node == null || !node.isObject()) {
                log.warn("动态 content 不是 JSON 对象 postId={} raw={}", post.getId(), abbreviate(raw));
                return Json.mapper().createObjectNode();
            }
            return node;
        } catch (Exception e) {
            log.warn("动态 content 解析失败 postId={} raw={}", post.getId(), abbreviate(raw));
            return Json.mapper().createObjectNode();
        }
    }

    private static String abbreviate(String raw) {
        return raw.length() <= 120 ? raw : raw.substring(0, 120) + "...";
    }

    private static int count(Integer value) {
        return value == null ? 0 : value;
    }

    private static Instant instant(LocalDateTime time, ZoneId zone) {
        return Timestamps.millis(time, zone);
    }
}
