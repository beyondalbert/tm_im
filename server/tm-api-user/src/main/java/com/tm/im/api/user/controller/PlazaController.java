package com.tm.im.api.user.controller;

import com.tm.im.api.user.auth.CurrentActor;
import com.tm.im.api.user.view.CommentCreateRequest;
import com.tm.im.api.user.view.CommentDeletedView;
import com.tm.im.api.user.view.CommentView;
import com.tm.im.api.user.view.FeedItemView;
import com.tm.im.api.user.view.LikeView;
import com.tm.im.api.user.view.PlazaViews;
import com.tm.im.api.user.view.PostCreateRequest;
import com.tm.im.api.user.view.PostDeletedView;
import com.tm.im.api.user.view.PostView;
import com.tm.im.api.user.view.SocialPageView;
import com.tm.im.common.api.ApiResponse;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.core.identity.AuthContext;
import com.tm.im.core.plaza.PlazaService;
import com.tm.im.domain.enums.Visibility;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.util.Locale;

/**
 * 广场（朋友圈）—— 03-rest-api.md §6.1–§6.6。
 *
 * <p>与 {@code FriendController} 同一分工：<b>这里没有任何业务判断</b>。
 * 可见性、配额、幂等、排序、写扩散都在 {@link PlazaService}。
 * 控制器只做三件机械的事：把路径/查询参数/请求体翻译成服务入参、
 * 把服务出参翻译成视图、把<b>协议层的写法</b>（{@code "PUBLIC"} 这类字符串）
 * 翻译成领域枚举。
 *
 * <p>路由前缀 {@code /v1/plaza} 下有两组路径：{@code /posts/**} 与 {@code /comments/**}。
 * {@code DELETE /v1/plaza/comments/{id}} 与 {@code DELETE /v1/plaza/posts/{id}}
 * 段数相同但首段不同，Spring 的模式匹配天然区分——不需要额外约束正则。
 */
@RestController
@RequestMapping("/v1/plaza")
public class PlazaController {

    private final PlazaService plaza;
    private final ZoneId databaseZone;

    public PlazaController(PlazaService plaza, ZoneId databaseZone) {
        this.plaza = plaza;
        this.databaseZone = databaseZone;
    }

    // ================================================================== §6.1 发布

    /** §6.1 发布动态。{@code visibility} 缺席默认 {@code PUBLIC}。 */
    @PostMapping("/posts")
    public ApiResponse<PostView> create(@CurrentActor AuthContext caller,
                                        @RequestBody PostCreateRequest body) {
        PlazaService.PostDraft draft = new PlazaService.PostDraft(
                body == null ? null : body.content(),
                visibility(body == null ? null : body.visibility()),
                body == null ? null : body.clientPostId());
        return ApiResponse.ok(PlazaViews.toView(plaza.create(caller.actorId(), draft), databaseZone));
    }

    // ================================================================== §6.2 信息流

    /** §6.2 信息流（好友优先）。 */
    @GetMapping("/feed")
    public ApiResponse<SocialPageView<FeedItemView>> feed(@CurrentActor AuthContext caller,
                                                          @RequestParam(required = false) Integer limit,
                                                          @RequestParam(required = false) String cursor) {
        PlazaService.Page<PlazaService.FeedEntry> page =
                plaza.feed(caller.actorId(), intOrZero(limit), cursor);
        return ApiResponse.ok(new SocialPageView<>(
                PlazaViews.feed(page.items(), databaseZone), page.nextCursor(), page.hasMore()));
    }

    // ================================================================== §6.3 某人的动态

    /** §6.3 某人的动态（非好友只能看到 {@code PUBLIC} 的那些）。 */
    @GetMapping("/users/{actorId}/posts")
    public ApiResponse<SocialPageView<FeedItemView>> authorPosts(
            @CurrentActor AuthContext caller,
            @PathVariable long actorId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        PlazaService.Page<PlazaService.FeedEntry> page =
                plaza.authorPosts(caller.actorId(), actorId, intOrZero(limit), cursor);
        return ApiResponse.ok(new SocialPageView<>(
                PlazaViews.feed(page.items(), databaseZone), page.nextCursor(), page.hasMore()));
    }

    // ================================================================== §6.4 删除动态

    /** §6.4 删除动态（仅作者本人）。 */
    @DeleteMapping("/posts/{postId}")
    public ApiResponse<PostDeletedView> delete(@CurrentActor AuthContext caller,
                                               @PathVariable long postId) {
        plaza.delete(caller.actorId(), postId);
        return ApiResponse.ok(new PostDeletedView(postId, true));
    }

    // ================================================================== §6.5 点赞

    /** §6.5 点赞。已赞过回 {@code 40907}（文档 §2.3 把它列为「幂等，忽略」）。 */
    @PostMapping("/posts/{postId}/like")
    public ApiResponse<LikeView> like(@CurrentActor AuthContext caller, @PathVariable long postId) {
        return ApiResponse.ok(PlazaViews.like(plaza.like(caller.actorId(), postId)));
    }

    /** §6.5 取消点赞。幂等：没赞过也回成功（见 {@code PlazaService#unlike} 的取舍）。 */
    @DeleteMapping("/posts/{postId}/like")
    public ApiResponse<LikeView> unlike(@CurrentActor AuthContext caller, @PathVariable long postId) {
        return ApiResponse.ok(PlazaViews.like(plaza.unlike(caller.actorId(), postId)));
    }

    // ================================================================== §6.6 评论

    /** §6.6 发评论（{@code reply_to_comment_id} 可选，必须属于同一条动态）。 */
    @PostMapping("/posts/{postId}/comments")
    public ApiResponse<CommentView> addComment(@CurrentActor AuthContext caller,
                                              @PathVariable long postId,
                                              @RequestBody CommentCreateRequest body) {
        if (body == null) {
            throw new TmException(ErrorCode.MISSING_PARAMETER, "请求体不能为空（需要 content）");
        }
        PlazaService.CommentEntry entry = plaza.addComment(
                caller.actorId(), postId, body.content(), body.replyToCommentId());
        return ApiResponse.ok(PlazaViews.toComment(entry, databaseZone));
    }

    /** §6.6 评论列表（时间正序）。 */
    @GetMapping("/posts/{postId}/comments")
    public ApiResponse<SocialPageView<CommentView>> listComments(
            @CurrentActor AuthContext caller,
            @PathVariable long postId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        PlazaService.Page<PlazaService.CommentEntry> page =
                plaza.listComments(caller.actorId(), postId, intOrZero(limit), cursor);
        return ApiResponse.ok(new SocialPageView<>(
                PlazaViews.comments(page.items(), databaseZone), page.nextCursor(), page.hasMore()));
    }

    /** §6.6 删除评论（评论作者或动态作者）。 */
    @DeleteMapping("/comments/{commentId}")
    public ApiResponse<CommentDeletedView> deleteComment(@CurrentActor AuthContext caller,
                                                         @PathVariable long commentId) {
        plaza.deleteComment(caller.actorId(), commentId);
        return ApiResponse.ok(new CommentDeletedView(commentId, true));
    }

    // ================================================================== 协议翻译

    /**
     * {@code visibility} 缺席默认 {@code PUBLIC}。
     *
     * <p>同时接受文档里的名字（{@code PUBLIC} / {@code FRIENDS_ONLY}）与数字
     * （{@code 1} / {@code 2}，即响应里那个值）：客户端把响应里的 {@code visibility}
     * 原样回传是最省事的做法，而拒绝它会让「读到的值不能写回去」变成一个坑。
     * 大小写不敏感，两侧空白忽略（HTTP 头/查询串转过一手时很常见）。
     */
    private static Visibility visibility(String raw) {
        if (raw == null || raw.isBlank()) {
            return Visibility.PUBLIC;
        }
        String value = raw.strip().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "PUBLIC", "1" -> Visibility.PUBLIC;
            case "FRIENDS_ONLY", "2" -> Visibility.FRIENDS_ONLY;
            default -> throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "visibility=" + raw + "（可选 PUBLIC / FRIENDS_ONLY，或 1 / 2）");
        };
    }

    /** {@code limit} 缺席 → 0，由服务层换成默认页大小（与好友/会话列表同一取舍）。 */
    private static int intOrZero(Integer limit) {
        return limit == null ? 0 : limit;
    }
}
