package com.tm.im.core.plaza;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.common.id.IdGenerator;
import com.tm.im.common.json.Json;
import com.tm.im.core.conversation.PageCursors;
import com.tm.im.core.media.MediaService;
import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.entity.FeedItem;
import com.tm.im.domain.entity.Media;
import com.tm.im.domain.entity.Post;
import com.tm.im.domain.entity.PostComment;
import com.tm.im.domain.enums.ActorType;
import com.tm.im.domain.enums.Visibility;
import com.tm.im.domain.repository.ActorRepository;
import com.tm.im.domain.repository.FeedItemRepository;
import com.tm.im.domain.repository.FriendshipRepository;
import com.tm.im.domain.repository.PostCommentRepository;
import com.tm.im.domain.repository.PostLikeRepository;
import com.tm.im.domain.repository.PostRepository;
import com.tm.im.domain.support.FeedScores;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * 广场（朋友圈）—— 03-rest-api.md §6 的实现（DESIGN §11.3）。
 *
 * <pre>
 *   发帖：校验 → 幂等 → 配额 → 存 post → 提交后异步写扩散到好友 feed_item
 *   读流：好友收件箱（feed_item，score 倒序）→ 读完后接着公开流（post，时间倒序）
 *   点赞/评论：写一行明细 + 同事务加 post 上的冗余计数
 * </pre>
 *
 * <h2>可见性只有一条判据</h2>
 *
 * <p>{@link #canSee} 是「谁能看这条动态」的<b>唯一</b>实现：作者本人、
 * {@code PUBLIC}、或「是好友」三者之一成立即可见。它同时被读流、点赞、评论、
 * 拉评论四个入口调用——四处各写一遍的话，漏掉的那一处就是一个越权读
 * （而它的表现只是「某接口多返回了一条别人的私密动态」）。
 *
 * <h2>信息流为什么分两段读</h2>
 *
 * <p>03-rest-api.md §6.2 承诺「好友动态优先、非好友的公开动态排在后面」。
 * 好友段来自 {@code feed_item}（写扩散的产物，读时零计算），公开段来自
 * {@code post}（{@code visibility=PUBLIC} 且作者不是我的好友）。
 * 两段各自走自己的索引、各自有序，由游标里的类型标签拼接（见 {@link PageCursors}）。
 *
 * <p>为什么不用一个 {@code ORDER BY score} 的联合查询：那只在「好友位比时间位高」
 * 时才成立，而 DESIGN §11.3 的位布局是「时间位在上、好友位在下」
 * （见 {@link FeedScores} 的注释）。把两者的差异摊在读取路径上，
 * 好过悄悄改掉一个已经被文档写死的公式。
 */
@Service
public class PlazaService implements PostDeletionPort {

    private static final Logger log = LoggerFactory.getLogger(PlazaService.class);

    /**
     * 一次请求里最多「继续往后翻」几轮。
     *
     * <p>每轮都会消费掉一批 {@code feed_item} 行，所以它<b>不是</b>死循环的护栏
     * （游标每轮都在前进），而是「一页里最多容忍多少条已删除的动态」的上限：
     * 超过它时宁可返回一个空页 + 游标（客户端会继续翻），也不让一个请求
     * 在服务端扫过几万行。
     */
    private static final int MAX_SKIP_ROUNDS = 20;

    private final PostRepository posts;
    private final FeedItemRepository feedItems;
    private final PostLikeRepository likes;
    private final PostCommentRepository comments;
    private final FriendshipRepository friendships;
    private final ActorRepository actors;
    private final MediaService media;
    private final IdGenerator idGenerator;
    private final PlazaProperties properties;
    private final ZoneId databaseZone;
    private final ExecutorService fanoutExecutor;

    public PlazaService(PostRepository posts,
                        FeedItemRepository feedItems,
                        PostLikeRepository likes,
                        PostCommentRepository comments,
                        FriendshipRepository friendships,
                        ActorRepository actors,
                        MediaService media,
                        IdGenerator idGenerator,
                        PlazaProperties properties,
                        ZoneId databaseZone,
                        @Qualifier("plazaFanoutExecutor") ExecutorService fanoutExecutor) {
        this.posts = posts;
        this.feedItems = feedItems;
        this.likes = likes;
        this.comments = comments;
        this.friendships = friendships;
        this.actors = actors;
        this.media = media;
        this.idGenerator = idGenerator;
        this.properties = properties;
        this.databaseZone = databaseZone;
        this.fanoutExecutor = fanoutExecutor;
    }

    // ================================================================== 通用类型

    /** 分页结果，{@code nextCursor} 为 null 表示没有更多。 */
    public record Page<T>(List<T> items, String nextCursor, boolean hasMore) {
    }

    /**
     * 信息流/个人页里的一项。
     *
     * @param friendAuthor 这一条是不是「我的好友」发的（§6.2 的 {@code is_friend_author}）
     * @param likedByMe    调用的这个人赞过它吗
     */
    public record FeedEntry(Post post, Actor author, boolean friendAuthor, boolean likedByMe) {
    }

    /** §6.1 的入参（协议层的字符串已经由控制器翻译成这里的形状）。 */
    public record PostDraft(JsonNode content, Visibility visibility, String clientPostId) {
    }

    /** §6.5 的结果：客户端据此就地更新那个数字，不必重拉整页。 */
    public record LikeOutcome(long postId, int likeCount, boolean liked) {
    }

    /** §6.6 列表里的一项。 */
    public record CommentEntry(PostComment comment, Actor author) {
    }

    // ================================================================== §6.1 发布

    /**
     * 发一条动态。
     *
     * <p>顺序是有意的（每一步都能独立短路）：
     * <ol>
     *   <li><b>校验内容</b>——最便宜，且失败与配额无关；</li>
     *   <li><b>幂等</b>——带 {@code client_post_id} 的重放必须在校验之后、
     *       配额之前判定：重放不该消耗一次配额，也不该因为「今天第 20 条」
     *       而被拒（那条动态今天已经发过了）；</li>
     *   <li><b>配额</b>——42902；</li>
     *   <li>落库，然后在<b>事务提交之后</b>异步写扩散。</li>
     * </ol>
     *
     * <p>扩散为什么必须等提交：异步线程会在另一个连接上读 {@code post} 表，
     * 而提交前它看不到那一行——那不是错误，只是「扩散白跑一趟」，
     * 但会让 post 与 feed_item 的可见性错位一小段时间。放在 afterCommit 里
     * 就没有这个窗口（代价是提交失败时不会有任何扩散，这正是我们要的）。
     */
    @Transactional
    public Post create(long actorId, PostDraft draft) {
        Actor author = requireActor(actorId);
        String content = normalizeContent(draft.content(), actorId);
        String clientPostId = cleanClientPostId(draft.clientPostId());

        if (clientPostId != null) {
            Post existing = posts.findByClientPostId(actorId, clientPostId).orElse(null);
            if (existing != null) {
                log.debug("发帖重放（幂等键命中）actorId={} clientPostId={} postId={}",
                        actorId, clientPostId, existing.getId());
                return existing;
            }
        }

        int usedToday = requireQuota(author);

        Post post = new Post();
        post.setId(idGenerator.nextId());
        post.setAuthorId(actorId);
        post.setContent(content);
        post.setVisibility(draft.visibility() == null ? Visibility.PUBLIC : draft.visibility());
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setClientPostId(clientPostId);
        post.setCreatedAt(LocalDateTime.now(databaseZone));
        try {
            posts.insert(post);
        } catch (DuplicateKeyException e) {
            // 并发重放：另一个请求刚写完同一把幂等键。回查并返回它，
            // 而不是把 DuplicateKey 暴露成 500——重复提交在弱网下是常态。
            if (clientPostId != null) {
                Post existing = posts.findByClientPostId(actorId, clientPostId).orElse(null);
                if (existing != null) {
                    log.debug("并发发帖重放 actorId={} clientPostId={} postId={}",
                            actorId, clientPostId, existing.getId());
                    return existing;
                }
            }
            throw e;
        }

        // 作者自己那一行<b>同步</b>写（就在这个事务里）：它只有一行，成本可以忽略，
        // 而且它保证了「发完立刻能看到自己的动态」这件事不依赖任何异步机制。
        // 好友那部分才走写扩散（可能几千行，见 scheduleFanout）。
        feedItems.saveAll(List.of(feedItem(actorId, post, false)));
        scheduleFanout(post);
        log.info("动态已发布 postId={} author={} visibility={} 今日第 {} 条（上限 {}）",
                post.getId(), actorId, post.getVisibility(), usedToday + 1, quotaOf(author));
        return post;
    }

    // ================================================================== §6.2 信息流

    /**
     * 信息流：好友段（收件箱）→ 公开流段（非好友的公开动态）。
     *
     * <p>游标是<b>有状态</b>的：它带着「读到哪一段、段内哪个位置」。
     * 这一点与「一个 ORDER BY 里的 offset」不同，也是它能做到「好友优先」的原因：
     * 两段的次序不是数据里的某一列，而是读取路径本身的先后。
     *
     * <p>两种段之间的转换点只有一处：{@link #feed} 里「好友段读完」那一个分支。
     */
    public Page<FeedEntry> feed(long actorId, int limit, String cursor) {
        int pageSize = pageSize(limit);
        PlazaCursor from = PlazaCursor.parse(cursor, databaseZone);

        List<FeedEntry> items = new ArrayList<>(pageSize);
        boolean friendBandExhausted = from.band() != Band.FRIEND;
        String next = null;
        boolean hasMore = false;

        // ---------- 好友段 ----------
        FeedItemRepository.Cursor feedCursor = from.feedPosition();
        int rounds = 0;
        while (!friendBandExhausted && items.size() < pageSize && rounds++ < MAX_SKIP_ROUNDS) {
            int want = pageSize - items.size() + 1;   // 多取一行用来判断「还有没有」
            List<FeedItem> rows = feedItems.pageByOwner(actorId, feedCursor, want);
            if (rows.isEmpty()) {
                friendBandExhausted = true;
                break;
            }
            boolean more = rows.size() == want;
            List<FeedItem> page = more ? rows.subList(0, want - 1) : rows;
            // 游标必须从**数据库行**推进（而不是从「渲染成功的那些」）：
            // 否则当一页的最后几行动态都被删除时，下一轮会停在原地，永远翻不过去。
            FeedItem last = page.get(page.size() - 1);
            feedCursor = new FeedItemRepository.Cursor(last.getScore(), last.getPostId());
            items.addAll(renderFeed(page, actorId));
            if (!more) {
                friendBandExhausted = true;
            }
        }

        if (!friendBandExhausted) {
            // 好友段自己就填满了一页：下一段要从「公开流的最前面」开始
            return new Page<>(List.copyOf(items), PageCursors.encodePlazaFeed(
                    feedCursor.score(), feedCursor.postId()), true);
        }

        // ---------- 公开流段 ----------
        PostRepository.Cursor publicFrom = from.band() == Band.PUBLIC
                ? from.postPosition() : null;
        int remaining = pageSize - items.size();
        if (remaining == 0) {
            // 好友段恰好填满一页：只探一下公开流里还有没有，有就给「公开流起始」游标。
            // 不能把游标指向任何一行——一段都没下发过，指向任何一行都会跳掉它。
            List<Post> probe = posts.pagePublicExcluding(excludedFromPublicBand(actorId), publicFrom, 1);
            if (!probe.isEmpty()) {
                next = PageCursors.encodePlazaPublicStart();
                hasMore = true;
            }
        } else {
            List<Post> rows = posts.pagePublicExcluding(excludedFromPublicBand(actorId), publicFrom,
                    remaining + 1);
            int take = Math.min(remaining, rows.size());
            List<Post> page = rows.subList(0, take);
            items.addAll(renderPosts(page, actorId, false));
            if (rows.size() > take && !page.isEmpty()) {
                Post last = page.get(page.size() - 1);
                next = PageCursors.encodePlazaPublic(epochMilli(last.getCreatedAt()), last.getId());
                hasMore = true;
            }
        }
        return new Page<>(List.copyOf(items), next, hasMore);
    }

    // ================================================================== §6.3 某人的动态

    /**
     * 某个 Actor 的动态（按时间倒序）。
     *
     * <p>可见性在这里<b>逐行</b>过滤：非好友只能看到 {@code PUBLIC} 的那部分。
     * 不能把过滤交给 SQL（{@code visibility = 1 OR author_id = me OR ...}）是因为
     * 「是不是好友」不在 {@code post} 表里，而那一条已经在上面的 {@link #canSee} 里。
     */
    public Page<FeedEntry> authorPosts(long callerId, long authorId, int limit, String cursor) {
        int pageSize = pageSize(limit);
        Actor author = requireActor(authorId);
        boolean friend = !isSelf(callerId, authorId) && areFriends(callerId, authorId);
        PostRepository.Cursor from = cursor == null || cursor.isBlank()
                ? null : postCursor(PageCursors.decodePlazaPosts(cursor));

        List<FeedEntry> items = new ArrayList<>(pageSize);
        PostRepository.Cursor position = from;
        int rounds = 0;
        boolean exhausted = false;
        while (items.size() < pageSize && rounds++ < MAX_SKIP_ROUNDS) {
            int want = pageSize - items.size() + 1;
            List<Post> rows = posts.pageByAuthor(authorId, position, want);
            if (rows.isEmpty()) {
                exhausted = true;
                break;
            }
            boolean more = rows.size() == want;
            List<Post> page = more ? rows.subList(0, want - 1) : rows;
            Post last = page.get(page.size() - 1);
            position = new PostRepository.Cursor(last.getCreatedAt(), last.getId());
            List<Post> visible = page.stream()
                    .filter(p -> canSee(p, callerId, authorId, friend))
                    .toList();
            items.addAll(renderPosts(visible, callerId, friend));
            if (!more) {
                exhausted = true;
            }
        }

        boolean hasMore = !exhausted;
        String next = hasMore && position != null
                ? PageCursors.encodePlazaPosts(epochMilli(position.createdAt()), position.postId())
                : null;
        // 游标在位置对上：{\code page} 的最后一行就是下一段的起点，
        // 而 items 可能因为可见性过滤而短于 pageSize——那只影响这一页的长度，
        // 不影响「从哪里继续」。
        return new Page<>(List.copyOf(items), next, hasMore);
    }

    // ================================================================== §6.4 删除

    /**
     * 删除动态（只有作者本人）。
     *
     * <p><b>级联是同步的、且在一个事务里</b>：{@code post} + {@code feed_item}
     * （按 {@code idx_post} 清）+ {@code post_like} + {@code post_comment}。
     * 不做软删除：{@code 40404} 说的是「动态不存在」，而软删除要求每个读路径
     * 都记得加那个条件——漏一处就是一个「已删除的动态还能被点赞」的越权写。
     */
    @Transactional
    public void delete(long actorId, long postId) {
        Post post = requirePost(postId);
        if (post.getAuthorId() == null || post.getAuthorId() != actorId) {
            throw new TmException(ErrorCode.PERMISSION_DENIED,
                    "只有作者能删自己的动态 postId=" + postId + " author=" + post.getAuthorId());
        }
        cascadeDelete(post);
    }

    /**
     * 后台删帖（M9）。
     *
     * <p><b>与 {@link #delete} 的差别只有一道判断</b>：不做「只有作者能删」的检查，
     * 其余（连带清收件箱、点赞、评论）完全一样。写到这个方法是故意的：
     * 审核删帖如果另走一套，两套代码里必有一套先长出「只删了 post 没删 feed_item」
     * 这类遗漏，而那个遗漏的表现是「已删除的动态仍能被好友在信息流里看到并发起点赞」——
     * 点赞会写进一个不存在的 post 的计数。
     */
    @Override
    @Transactional
    public void deleteAsAdmin(long postId) {
        cascadeDelete(requirePost(postId));
    }

    /**
     * 级联清理。
     *
     * <p>顺序是「先清引用方，再删被引用方」：{@code feed_item} / {@code post_like} /
     * {@code post_comment} 都有 {@code post_id} 索引，任何一个失败都不会留下
     * 「post 没了但引用还在」的状态（那个状态下点赞的计数更新会打在不存在的行上）。
     */
    private void cascadeDelete(Post post) {
        long postId = post.getId();
        int feedRows = feedItems.deleteByPostId(postId);
        int likeRows = likes.deleteByPostId(postId);
        int commentRows = comments.deleteByPostId(postId);
        posts.deleteById(postId);
        log.info("动态已删除 postId={} author={} 连带 feedItem={} like={} comment={}",
                postId, post.getAuthorId(), feedRows, likeRows, commentRows);
    }

    // ================================================================== §6.5 点赞

    /** 点赞。已赞过回 {@code 40907}（07-errors-limits.md §2.3）。 */
    @Transactional
    public LikeOutcome like(long actorId, long postId) {
        Post post = requireVisiblePost(postId, actorId);
        int before = countOf(post.getLikeCount());
        boolean added = likes.like(postId, actorId, LocalDateTime.now(databaseZone));
        if (!added) {
            throw new TmException(ErrorCode.ALREADY_LIKED, "postId=" + postId + " actorId=" + actorId);
        }
        posts.addLikeCount(postId, 1);
        // 返回的是「按读到的值推算」的结果，不是重新查一遍：并发的别人点赞会让它偏小，
        // 而客户端要的只是「点完之后大概是多少」——为了它多一次回表不划算。
        return new LikeOutcome(postId, before + 1, true);
    }

    /**
     * 取消点赞。<b>幂等</b>：没赞过也回成功。
     *
     * <p>与点赞不对称是有意的：点赞的重复提交是「我手滑点了两下」
     * （客户端有明确的目标状态：已赞），而取消的重复提交是「同步重试」——
     * 客户端的目标状态（未赞）已经成立，报错只会让它以为没成功而继续重试。
     */
    @Transactional
    public LikeOutcome unlike(long actorId, long postId) {
        Post post = requireVisiblePost(postId, actorId);
        int before = countOf(post.getLikeCount());
        boolean removed = likes.unlike(postId, actorId);
        if (removed) {
            posts.addLikeCount(postId, -1);
        }
        // 计数永远不会被减到负数：只有「真的删掉了一行」才减（见仓储注释）。
        return new LikeOutcome(postId, Math.max(0, before - (removed ? 1 : 0)), false);
    }

    // ================================================================== §6.6 评论

    /** 发一条评论（可选回复某条评论）。 */
    @Transactional
    public CommentEntry addComment(long actorId, long postId, String content, Long replyToCommentId) {
        Post post = requireVisiblePost(postId, actorId);
        Actor author = requireActor(actorId);
        String text = cleanComment(content);
        if (replyToCommentId != null) {
            PostComment target = comments.findById(replyToCommentId).orElseThrow(() ->
                    new TmException(ErrorCode.INVALID_PARAMETER,
                            "reply_to_comment_id=" + replyToCommentId + " 不存在"));
            if (!java.util.Objects.equals(target.getPostId(), postId)) {
                // 跨动态引用会让「拉这条动态的评论」永远看不到被回复的那条，
                // 而客户端会显示一个指向别处的回复——所以这里直接判非法输入。
                throw new TmException(ErrorCode.INVALID_PARAMETER,
                        "reply_to_comment_id=" + replyToCommentId + " 不属于动态 " + postId);
            }
        }

        PostComment comment = new PostComment();
        comment.setId(idGenerator.nextId());
        comment.setPostId(postId);
        comment.setAuthorId(actorId);
        comment.setContent(text);
        comment.setReplyToCommentId(replyToCommentId);
        comment.setCreatedAt(LocalDateTime.now(databaseZone));
        comments.insert(comment);
        posts.addCommentCount(postId, 1);
        log.debug("评论已写入 commentId={} postId={} author={} replyTo={}",
                comment.getId(), postId, actorId, replyToCommentId);
        return new CommentEntry(comment, author);
    }

    /** 评论列表（时间正序，一条讨论线从上往下读）。 */
    public Page<CommentEntry> listComments(long actorId, long postId, int limit, String cursor) {
        int pageSize = pageSize(limit);
        requireVisiblePost(postId, actorId);
        PostCommentRepository.Cursor from = cursor == null || cursor.isBlank()
                ? null : commentCursor(PageCursors.decodePlazaComment(cursor));
        List<PostComment> rows = comments.pageByPost(postId, from, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<PostComment> page = hasMore ? rows.subList(0, pageSize) : rows;
        Map<Long, Actor> byId = loadActors(page.stream().map(PostComment::getAuthorId).toList());
        List<CommentEntry> items = new ArrayList<>(page.size());
        for (PostComment row : page) {
            items.add(new CommentEntry(row, byId.get(row.getAuthorId())));
        }
        String next = hasMore && !page.isEmpty()
                ? PageCursors.encodePlazaComment(epochMilli(page.get(page.size() - 1).getCreatedAt()),
                        page.get(page.size() - 1).getId())
                : null;
        return new Page<>(List.copyOf(items), next, hasMore);
    }

    /**
     * 删一条评论：<b>评论作者本人或动态作者</b>。
     *
     * <p>为什么动态作者也能删：评论区是那条动态的一部分，
     * 「我不能清理挂在自己动态下面的评论」是一条说不通的产品规则。
     * 两类人都不是时回 {@code 40302}（已认证、无权限），不用 40400：
     * 那条评论确实存在，把它说成不存在会让客户端以为是自己拼错了 id。
     */
    @Transactional
    public void deleteComment(long actorId, long commentId) {
        PostComment comment = comments.findById(commentId).orElseThrow(() ->
                new TmException(ErrorCode.NOT_FOUND, "commentId=" + commentId));
        Post post = posts.findById(comment.getPostId()).orElseThrow(() ->
                new TmException(ErrorCode.POST_NOT_FOUND, "动态不存在 postId=" + comment.getPostId()));
        boolean isCommentAuthor = comment.getAuthorId() != null && comment.getAuthorId() == actorId;
        boolean isPostAuthor = post.getAuthorId() != null && post.getAuthorId() == actorId;
        if (!isCommentAuthor && !isPostAuthor) {
            throw new TmException(ErrorCode.PERMISSION_DENIED,
                    "commentId=" + commentId + " 既不是评论作者也不是动态作者的 actorId=" + actorId);
        }
        if (comments.deleteById(commentId)) {
            posts.addCommentCount(comment.getPostId(), -1);
        }
        log.info("评论已删除 commentId={} postId={} by={}", commentId, comment.getPostId(), actorId);
    }

    // ================================================================== 可见性与基础查询

    /**
     * 「这个 Actor 能不能看这条动态」——全模块唯一的可见性判据。
     *
     * @param authorId 作者（已经查出来的），避免每行查一次 actor
     * @param friend   {@code callerId} 与作者是不是好友（由调用方查一次）
     */
    private static boolean canSee(Post post, long callerId, long authorId, boolean friend) {
        if (authorId == callerId) {
            return true;
        }
        if (post.getVisibility() == null || post.getVisibility() == Visibility.PUBLIC) {
            return true;
        }
        return friend;
    }

    private Post requirePost(long postId) {
        return posts.findById(postId).orElseThrow(() ->
                new TmException(ErrorCode.POST_NOT_FOUND, "postId=" + postId));
    }

    /**
     * 取一条「调用者有权看到」的动态。
     *
     * <p>看不到时回 {@code 40302} 而不是 {@code 40404}：两者都会泄露「这个 id 存在」，
     * 所以拿泄露当理由选错码是没有根据的；而 40302 能告诉客户端
     * 「先去加好友」，那是它真正该做的动作。
     */
    private Post requireVisiblePost(long postId, long callerId) {
        Post post = requirePost(postId);
        long authorId = post.getAuthorId() == null ? 0 : post.getAuthorId();
        boolean friend = !isSelf(callerId, authorId) && areFriends(callerId, authorId);
        if (!canSee(post, callerId, authorId, friend)) {
            throw new TmException(ErrorCode.PERMISSION_DENIED,
                    "默认可见的界限：postId=" + postId + " visibility=" + post.getVisibility()
                            + " callerId=" + callerId);
        }
        return post;
    }

    private Actor requireActor(long actorId) {
        return actors.findById(actorId).orElseThrow(() -> new TmException(
                ErrorCode.ACTOR_NOT_FOUND, "actorId=" + actorId));
    }

    private boolean areFriends(long actorX, long actorY) {
        return friendships.areFriends(actorX, actorY);
    }

    private static boolean isSelf(long actorX, long actorY) {
        return actorX == actorY;
    }

    private int pageSize(int limit) {
        int max = Math.max(1, properties.getMaxPageSize());
        if (limit <= 0) {
            return Math.min(max, Math.max(1, properties.getPageSize()));
        }
        return Math.min(limit, max);
    }

    private Map<Long, Actor> loadActors(Collection<Long> actorIds) {
        List<Long> distinct = actorIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, Actor> out = new HashMap<>();
        for (Actor actor : actors.findByIds(distinct)) {
            out.put(actor.getId(), actor);
        }
        return out;
    }

    private static int countOf(Integer value) {
        return value == null ? 0 : value;
    }

    private long epochMilli(LocalDateTime time) {
        return time.atZone(databaseZone).toInstant().toEpochMilli();
    }

    /** 毫秒 → 库里的墙上时间（游标的反向换算，口径必须是同一个时区）。 */
    private LocalDateTime wallTime(long epochMilli) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), databaseZone);
    }

    private PostRepository.Cursor postCursor(PageCursors.PlazaPostsCursor from) {
        return new PostRepository.Cursor(wallTime(from.atMillis()), from.postId());
    }

    private PostCommentRepository.Cursor commentCursor(PageCursors.PlazaCommentCursor from) {
        return new PostCommentRepository.Cursor(wallTime(from.atMillis()), from.commentId());
    }

    // ================================================================== 渲染（实体→材料）

    /**
     * 把收件箱行变成可渲染的材料。
     *
     * <p>动态可能已经被删（收件箱行的清理是同步的，但两个请求之间存在时间窗）：
     * 那几行<b>静默跳过</b>并记 DEBUG——它们不是错误，只是异步清理的正常中间态。
     */
    private List<FeedEntry> renderFeed(List<FeedItem> rows, long viewerId) {
        List<Long> postIds = rows.stream().map(FeedItem::getPostId).distinct().toList();
        List<Post> found = posts.findByIds(postIds);
        Map<Long, Post> byId = new HashMap<>();
        for (Post post : found) {
            byId.put(post.getId(), post);
        }
        List<Post> ordered = new ArrayList<>(rows.size());
        for (FeedItem row : rows) {
            Post post = byId.get(row.getPostId());
            if (post == null) {
                log.debug("收件箱行的动态已不存在，跳过 postId={} owner={}",
                        row.getPostId(), row.getOwnerId());
                continue;
            }
            ordered.add(post);
        }
        return renderPosts(ordered, viewerId, true);
    }

    /**
     * 动态列表 → 材料列表（补作者、补「我赞过吗」）。
     *
     * @param friendBand 这一批是不是「好友段」（收件箱）里的——若是，则
     *                   {@code is_friend_author} 由「作者不是我自己」决定；
     *                   公开段里恒为 false（那条 SQL 已经把好友排掉了）。
     *                   收件箱里既有好友的行、也有作者自己的行，两者必须区分：
     *                   把自己的动态标成「好友来源」会让客户端多一个徽标。
     */
    private List<FeedEntry> renderPosts(List<Post> page, long viewerId, boolean friendBand) {
        if (page.isEmpty()) {
            return List.of();
        }
        Map<Long, Actor> authors = loadActors(page.stream().map(Post::getAuthorId).toList());
        Set<Long> liked = likes.findLikedPostIds(viewerId, page.stream().map(Post::getId).toList());
        List<FeedEntry> out = new ArrayList<>(page.size());
        for (Post post : page) {
            Actor author = authors.get(post.getAuthorId());
            if (author == null) {
                // 作者不存在（账号被删）。不回一条 author=null 的动态：视图层会在渲染时 NPE，
                // 而客户端会拿到一个没有任何归属的卡片。
                log.error("动态的作者不存在 postId={} authorId={}", post.getId(), post.getAuthorId());
                continue;
            }
            boolean friendAuthor = friendBand && !isSelf(viewerId, post.getAuthorId());
            out.add(new FeedEntry(post, author, friendAuthor, liked.contains(post.getId())));
        }
        return out;
    }

    // ================================================================== 写扩散

    /**
     * 排一次异步写扩散（DESIGN §11.3）。
     *
     * <p>三件事决定了它的形状：
     * <ol>
     *   <li><b>不阻塞发帖</b>：扩散要写「好友数」行，从几十到几千不等；</li>
     *   <li><b>不丢一致性责任</b>：{@code feed_item} 是派生数据，失败只记日志、不重试
     *       （重试需要持久化队列，那是另一个量级的东西）；</li>
     *   <li><b>在提交之后跑</b>：否则异步线程可能读不到刚插入的 post 行。</li>
     * </ol>
     */
    private void scheduleFanout(Post post) {
        Runnable task = () -> {
            try {
                fanout(post);
            } catch (RuntimeException e) {
                // 扩散失败不影响那条动态本身（它已经可见于作者页与公开流）。
                // 这里必须捕到底：线程池里的异常默认只会打印到 stderr。
                log.error("写扩散失败 postId={} author={}", post.getId(), post.getAuthorId(), e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(task, post);
                }
            });
        } else {
            submit(task, post);
        }
    }

    private void submit(Runnable task, Post post) {
        try {
            fanoutExecutor.execute(task);
        } catch (RuntimeException e) {
            // 上一句池已关（应用正在停机）或被拒：两种情况下扩散都不该影响发帖结果。
            log.warn("写扩散任务提交失败 postId={}（应用可能正在停机）", post.getId(), e);
        }
    }

    /**
     * 真正写出收件箱行：每个好友一行。
     *
     * <p>作者自己那一行不在这里—— 它在 {@link #create} 的事务里同步写完（见那里的注释）：
     * 异步的任务可能被丢掉，而「自己的动态不出现在自己信息流里」是不可接受的；
     * 「好友没收到」只是一个可以补的缺口（公开流与作者个人页仍然看得到）。
     *
     * <p>大 V（好友数超过 {@code tm.feed.celebrity-threshold}）整个跳过：
     * 那意味着一次发帖要写几万行，已经不该在一个后台任务里做了。
     */
    private void fanout(Post post) {
        long authorId = post.getAuthorId();
        int threshold = properties.getCelebrityThreshold();
        // 多取一条用来区分「恰好等于阈值」与「超过阈值」
        List<Long> friends = friendships.listFriendIds(authorId, threshold + 1);
        if (friends.size() > threshold) {
            log.warn("作者好友数超过 tm.feed.celebrity-threshold={}，跳过写扩散（读扩散尚未实现："
                            + "这条动态不会出现在其好友的信息流里）postId={} author={} 好友数≥{}",
                    threshold, post.getId(), authorId, friends.size());
            return;
        }
        List<FeedItem> rows = new ArrayList<>(friends.size());
        for (long friendId : friends) {
            rows.add(feedItem(friendId, post, true));
        }
        feedItems.saveAll(rows);
        log.debug("写扩散完成 postId={} author={} 好友行数={}", post.getId(), authorId, rows.size());
    }

    /**
     * 一行收件箱。
     *
     * @param owner      收件人
     * @param friendAuthor 收件人是否与作者是好友——它就是好友加权位，
     *                     对作者自己（在信息流里 {@code is_friend_author=false}）
     *                     与好友两类行给出不同的分值
     */
    private FeedItem feedItem(long owner, Post post, boolean friendAuthor) {
        FeedItem item = new FeedItem();
        item.setOwnerId(owner);
        item.setScore(FeedScores.of(post.getCreatedAt(), databaseZone,
                properties.getFriendBoost(), friendAuthor, post.getId()));
        item.setPostId(post.getId());
        item.setAuthorId(post.getAuthorId());
        return item;
    }

    /**
     * 公开流要排除的作者 = 我的好友 + <b>我自己</b>。
     *
     * <p>排除好友是为了去重：他们的动态已经在好友段（收件箱）里下发过了。
     * 排除自己也是同一件事——自己的动态同样在收件箱里有一行（见 {@code fanout}），
     * 不排除的话自己的每一条公开动态都会在信息流里出现两次。
     *
     * <p>这个列表会整段拼进 SQL 的 {@code NOT IN (...)}，所以有一道上限
     * （{@code tm.feed.friend-set-cap}）。超过上限时记 WARN 并继续——
     * 少排除掉的那部分好友会让公开流里出现重复动态（他们已经在好友段里出现过），
     * 而重复比「整段公开流打不开」轻得多。
     */
    private List<Long> excludedFromPublicBand(long actorId) {
        int cap = Math.max(1, properties.getFriendSetCap());
        List<Long> ids = friendships.listFriendIds(actorId, cap);
        if (ids.size() >= cap) {
            log.warn("好友数达到 tm.feed.friend-set-cap={}，公开流的好友排除列表被截断，"
                    + "可能重复下发部分好友的动态 actorId={}", cap, actorId);
        }
        List<Long> out = new ArrayList<>(ids.size() + 1);
        out.add(actorId);
        out.addAll(ids);
        return out;
    }

    // ================================================================== 入参校验

    /**
     * 校验并<b>规范化</b>动态正文。
     *
     * <p>与消息 content 的处理（{@code MessageService} 只校验必需字段、原样存储）
     * 刻意的不同：动态是永久、公开的内容，把客户端发来的任意字段一并存下来并回显，
     * 等于给客户端一个「往别人的时间轴里塞自定义字段」的通道
     * （它们会出现在每个拉这条动态的客户端里）。所以这里只保留
     * {@code text} 与 {@code images} 两个字段，其余一律丢弃。
     *
     * <p>图片的 {@code width}/{@code height} 也<b>以库里的为准</b>：
     * 客户端传的值只用于它自己的本地占位，而卡片尺寸必须每处一致——
     * 否则同一条动态在不同客户端上会占不同高度（看着像卡顿）。
     */
    private String normalizeContent(JsonNode content, long authorId) {
        if (content == null || !content.isObject()) {
            throw new TmException(ErrorCode.MISSING_PARAMETER, "content 必须是对象（§6.1）");
        }
        String text = null;
        JsonNode textNode = content.get("text");
        if (textNode != null && !textNode.isNull()) {
            if (!textNode.isTextual()) {
                throw new TmException(ErrorCode.INVALID_PARAMETER, "content.text 必须是字符串");
            }
            text = textNode.asText();
            if (text.length() > properties.getMaxTextLength()) {
                throw new TmException(ErrorCode.CONTENT_TOO_LONG,
                        "text 长度 " + text.length() + " 超过上限 " + properties.getMaxTextLength());
            }
        }

        ObjectNode out = Json.mapper().createObjectNode();
        ArrayNode images = Json.mapper().createArrayNode();
        JsonNode imagesNode = content.get("images");
        if (imagesNode != null && !imagesNode.isNull()) {
            if (!imagesNode.isArray()) {
                throw new TmException(ErrorCode.INVALID_PARAMETER, "content.images 必须是数组");
            }
            if (imagesNode.size() > properties.getMaxImages()) {
                throw new TmException(ErrorCode.INVALID_PARAMETER,
                        "images 数量 " + imagesNode.size() + " 超过上限 " + properties.getMaxImages());
            }
            for (JsonNode image : imagesNode) {
                images.add(normalizeImage(image, authorId));
            }
        }

        if ((text == null || text.isBlank()) && images.isEmpty()) {
            throw new TmException(ErrorCode.MISSING_PARAMETER,
                    "content.text 与 content.images 至少得有一个（空动态没有意义）");
        }
        if (text != null) {
            out.put("text", text);
        }
        if (!images.isEmpty()) {
            out.set("images", images);
        }
        return Json.write(out);
    }

    /**
     * 单张图：{@code media_id} 必须存在、且必须是<b>自己上传的</b>。
     *
     * <p>与 {@code MessageService.validateImage} 的取舍不同（那里刻意不查媒体表）：
     * 发消息在热路径上、每秒上千条，而发动态是每天几十条的事——
     * 为它多一次点查换「不会引用到别人的图」是划算的。
     */
    private ObjectNode normalizeImage(JsonNode image, long authorId) {
        if (image == null || !image.isObject()) {
            throw new TmException(ErrorCode.INVALID_PARAMETER, "images 的每一项必须是对象");
        }
        JsonNode mediaIdNode = image.get("media_id");
        if (mediaIdNode == null || !mediaIdNode.isNumber() || mediaIdNode.asLong() <= 0) {
            throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "images[].media_id 必须是正整数（先调 POST /v1/media 上传）");
        }
        long mediaId = mediaIdNode.asLong();
        Media row = media.require(mediaId);   // 不存在 → 40008
        if (row.getOwnerId() == null || row.getOwnerId() != authorId) {
            // 引用别人上传的图：回 40008 而不是 40302—— 对方（受害者）
            // 不该因为别人拼错了 id 而收到任何与自己的图相关的报错。
            throw new TmException(ErrorCode.MEDIA_NOT_FOUND,
                    "mediaId=" + mediaId + " 不属于 actorId=" + authorId);
        }
        ObjectNode out = Json.mapper().createObjectNode();
        out.put("media_id", mediaId);
        if (row.getWidth() != null) {
            out.put("width", row.getWidth());
        }
        if (row.getHeight() != null) {
            out.put("height", row.getHeight());
        }
        return out;
    }

    /**
     * 评论正文：去空白、非空、长度上限。
     *
     * <p>{@code content} 在文档里是字符串（不是对象）——评论没有图片与外层的结构，
     * 套一层 JSON 只会把一次长度校验变成一次「取字段再校验」。
     */
    private String cleanComment(String content) {
        if (content == null || content.isBlank()) {
            throw new TmException(ErrorCode.MISSING_PARAMETER, "content 不能为空");
        }
        String clean = content.strip();
        if (clean.length() > properties.getMaxCommentLength()) {
            throw new TmException(ErrorCode.CONTENT_TOO_LONG,
                    "评论长度 " + clean.length() + " 超过上限 " + properties.getMaxCommentLength());
        }
        return clean;
    }

    private static String cleanClientPostId(String clientPostId) {
        if (clientPostId == null || clientPostId.isBlank()) {
            return null;
        }
        String clean = clientPostId.strip();
        if (clean.length() > 64) {
            throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "client_post_id 长度 " + clean.length() + " 超过上限 64");
        }
        return clean;
    }

    /**
     * 配额校验，返回「今天已发多少条」（写入前的那一次计数）。
     *
     * <p>把计数返回给调用方而不是让它再count一次：写入之后的计数会包含刚插进去的那一条，
     * 用它拼出来的日志会多算一条（“今日第 21 条（上限 20）”），而那种日志会让人怀疑配额算错了。
     *
     * @throws TmException 42902
     */
    private int requireQuota(Actor author) {
        int quota = quotaOf(author);
        int used = todayCount(author.getId());
        if (used >= quota) {
            throw new TmException(ErrorCode.DAILY_QUOTA_EXCEEDED,
                    "今日已发 " + used + " 条（上限 " + quota + " 条/天）");
        }
        return used;
    }

    /**
     * 今日已发多少条。
     *
     * <p>起算点是 {@code databaseZone} 的当天零点，与好友请求配额同口径
     * （{@code FriendService#dailyQuota}）：用 UTC 算会让「配额什么时候重置」
     * 与运维手上的时区对不上，而那种偏移只有每天的头尾几小时才看得出来。
     */
    private int todayCount(long actorId) {
        LocalDateTime since = LocalDate.now(databaseZone).atStartOfDay();
        return posts.countByAuthorSince(actorId, since);
    }

    private int quotaOf(Actor actor) {
        // 与好友请求同一套依据：人类的配额防手滑，Agent 的配额防批量刷屏。
        // 读 actor_type 在这里是合法的（它是**策略参数**的输入，不是权限判据）。
        return actor.getActorType() == ActorType.AGENT
                ? properties.getAgentPostDailyQuota()
                : properties.getPostDailyQuota();
    }

    // ================================================================== 游标

    /** 信息流的两段。 */
    private enum Band {
        /** 好友段：收件箱（{@code feed_item}）。 */
        FRIEND,

        /** 公开流段：非好友的 {@code PUBLIC} 动态。 */
        PUBLIC
    }

    /**
     * 已经过类型校验的信息流游标。
     *
     * <p>两种段的位置类型不同（score + post_id vs 时间 + post_id），所以它们不在一个
     * record 里共享字段——字段一旦共享，"好友段也能拿到 created_at" 这种无意义的状态
     * 就会在构造时变成合法状态，而它的表现是「某种游标组合下分页静默错位」。
     */
    private record PlazaCursor(Band band,
                              FeedItemRepository.Cursor feedPosition,
                              PostRepository.Cursor postPosition) {

        static PlazaCursor friend(FeedItemRepository.Cursor position) {
            return new PlazaCursor(Band.FRIEND, position, null);
        }

        static PlazaCursor publicBand(PostRepository.Cursor position) {
            return new PlazaCursor(Band.PUBLIC, null, position);
        }

        /**
         * 解析客户端传来的游标。
         *
         * <p>空游标 = 「好友段的第一页」——这是§6.2 的顺序承诺：
         * 新用户打开广场，先看到的是好友，而不是陌生人。
         */
        static PlazaCursor parse(String cursor, ZoneId zone) {
            if (cursor == null || cursor.isBlank()) {
                return friend(null);
            }
            String type = PageCursors.typeOf(cursor);
            if (type == null) {
                // 不是合法的 base64 + JSON 对象（或没有 t）：借 decode 抛出带原值的 40010。
                PageCursors.decodePlazaFeed(cursor);
                throw new IllegalStateException("不可达：坏游标本应在上一行抛 40010");
            }
            return switch (type) {
                case PageCursors.TYPE_PLAZA_FEED -> {
                    PageCursors.PlazaFeedCursor c = PageCursors.decodePlazaFeed(cursor);
                    yield friend(new FeedItemRepository.Cursor(c.score(), c.postId()));
                }
                case PageCursors.TYPE_PLAZA_PUBLIC -> {
                    PageCursors.PlazaPublicCursor p = PageCursors.decodePlazaPublic(cursor);
                    yield p.atMillis() == null || p.postId() == null
                            ? publicBand(null)      // 「公开流从最新一条开始」
                            : publicBand(new PostRepository.Cursor(
                                    LocalDateTime.ofInstant(Instant.ofEpochMilli(p.atMillis()), zone),
                                    p.postId()));
                }
                // 把会话/消息/好友列表的游标贴到信息流上：类型不同就必须报错。
                // 宽容接受（当成空游标）会让客户端拿到一个「从头开始」的列表，
                // 而它以为自己正在翻页——重复下发比 40010 难排查得多。
                default -> throw new TmException(ErrorCode.INVALID_CURSOR,
                        "信息流要 pfeed / ppub 游标，而 t=" + type);
            };
        }
    }
}

