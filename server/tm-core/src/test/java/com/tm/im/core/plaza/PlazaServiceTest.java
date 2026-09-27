package com.tm.im.core.plaza;

import com.fasterxml.jackson.databind.JsonNode;
import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.common.json.Json;
import com.tm.im.core.conversation.InMemoryActors;
import com.tm.im.core.conversation.SequentialIds;
import com.tm.im.core.friend.InMemoryFriendships;
import com.tm.im.core.media.InMemoryMediaRepository;
import com.tm.im.core.media.InMemoryMediaStore;
import com.tm.im.core.media.MediaService;
import com.tm.im.domain.entity.Actor;
import com.tm.im.domain.entity.FeedItem;
import com.tm.im.domain.entity.Media;
import com.tm.im.domain.entity.Post;
import com.tm.im.domain.enums.ActorType;
import com.tm.im.domain.enums.FriendshipStatus;
import com.tm.im.domain.enums.Visibility;
import com.tm.im.domain.support.FeedScores;
import com.tm.im.storage.media.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PlazaService} 的规则（03-rest-api.md §6）。
 *
 * <p>这里盯着的是「改坏了照样能跑」那一类规则：仅好友可见的动态外泄、
 * 重复点赞把计数加两次、取消点赞把计数减成负数、游标翻页重复或漏项、
 * 删除动态时漏清收件箱、幂等键没有拦住重放、大 V 阈值被绕过（一条动态写出十万行）。
 * 它们都不会报错，只会在某天以「数字不太对」「少了几条动态」的形式出现。
 *
 * <p>刻意不在这里测 SQL：索引、游标谓词的括号、ShardingSphere 的改写都在
 * {@code PlazaHttpIT} 那一侧（真实 MySQL）覆盖。
 */
class PlazaServiceTest {

    private static final long ALICE = 1001L;
    private static final long BOB = 1002L;
    private static final long CAROL = 1003L;
    private static final long STRANGER = 1009L;
    private static final long BOT = 2002L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private InMemoryPosts posts;
    private InMemoryFeedItems feedItems;
    private InMemoryPostLikes likes;
    private InMemoryPostComments comments;
    private InMemoryFriendships friendships;
    private InMemoryActors actors;
    private InMemoryMediaRepository mediaRows;
    private PlazaProperties properties;

    @BeforeEach
    void setUp() {
        posts = new InMemoryPosts();
        feedItems = new InMemoryFeedItems();
        likes = new InMemoryPostLikes();
        comments = new InMemoryPostComments();
        friendships = new InMemoryFriendships();
        actors = new InMemoryActors();
        mediaRows = new InMemoryMediaRepository();
        properties = new PlazaProperties();

        actors.put(ALICE, "alice");
        actors.put(BOB, "bob");
        actors.put(CAROL, "carol");
        actors.put(STRANGER, "stranger");
        Actor bot = actors.put(BOT, "weather_bot");
        bot.setActorType(ActorType.AGENT);
    }

    /** 每个用例自己建服务：扩散线程池是注入点，几条用例要换掉它。 */
    private PlazaService service() {
        return service(new InlineExecutor());
    }

    private PlazaService service(ExecutorService fanout) {
        MediaService media = new MediaService(mediaRows, new InMemoryMediaStore(),
                new SequentialIds(), new StorageProperties(), ZONE);
        return new PlazaService(posts, feedItems, likes, comments, friendships, actors, media,
                new SequentialIds(), properties, ZONE, fanout);
    }

    // ================================================================ §6.1 发布

    @Test
    @DisplayName("发布：默认 PUBLIC，落库的 content 只保留 text 与 images（多余字段一律丢弃）")
    void createNormalizesContent() {
        Post post = service().create(ALICE, draft("""
                {"text":"今天天气不错","evil":{"inject":"x"},"like_count":9999}""", null, null));

        assertThat(post.getVisibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(post.getAuthorId()).isEqualTo(ALICE);
        assertThat(post.getLikeCount()).isZero();
        assertThat(post.getCommentCount()).isZero();
        assertThat(post.getClientPostId()).isNull();
        JsonNode content = node(post.getContent());
        assertThat(fieldNames(content)).containsExactly("text");
        assertThat(content.path("text").asText()).isEqualTo("今天天气不错");
    }

    @Test
    @DisplayName("发布：FRIENDS_ONLY 原样落库；图片宽高以库里的为准（客户端传的尺寸不可信）")
    void createKeepsVisibilityAndDatabaseImageSize() {
        seedMedia(ALICE, 7002L, 1280, 720);
        Post post = service().create(ALICE, draft(
                "{\"images\":[{\"media_id\":7002,\"width\":1,\"height\":1}]}",
                Visibility.FRIENDS_ONLY, null));

        assertThat(posts.get(post.getId()).getVisibility()).isEqualTo(Visibility.FRIENDS_ONLY);
        JsonNode image = node(post.getContent()).path("images").path(0);
        assertThat(image.path("media_id").asLong()).isEqualTo(7002L);
        assertThat(image.path("width").asInt()).isEqualTo(1280);
        assertThat(image.path("height").asInt()).isEqualTo(720);
    }

    @Test
    @DisplayName("发布：空动态（既无 text 也无 images）→ 40001，且一行都不写")
    void createRejectsEmptyContent() {
        assertThat(codeOf(() -> service().create(ALICE, draft("{}", null, null))))
                .isEqualTo(ErrorCode.MISSING_PARAMETER);
        assertThat(posts.size()).isZero();
    }

    @Test
    @DisplayName("发布：正文超长 → 40006（报错而不是截断）")
    void createRejectsTooLongText() {
        String text = "x".repeat(properties.getMaxTextLength() + 1);
        assertThat(codeOf(() -> service().create(ALICE,
                draft("{\"text\":\"" + text + "\"}", null, null))))
                .isEqualTo(ErrorCode.CONTENT_TOO_LONG);
    }

    @Test
    @DisplayName("发布：图片必须是自己的 —— 引用别人的图回 40008，且不落库")
    void createRejectsForeignMedia() {
        seedMedia(BOB, 7001L, 100, 200);
        assertThat(codeOf(() -> service().create(ALICE,
                draft("{\"images\":[{\"media_id\":7001}]}", null, null))))
                .isEqualTo(ErrorCode.MEDIA_NOT_FOUND);
        assertThat(posts.size()).isZero();
    }

    @Test
    @DisplayName("发布：图片数超过上限 → 40002；media_id 不是正整数 → 40002")
    void createRejectsBadImages() {
        StringBuilder json = new StringBuilder("{\"images\":[");
        for (int i = 0; i <= properties.getMaxImages(); i++) {
            long mediaId = 7100L + i;
            seedMedia(ALICE, mediaId, 10, 10);
            json.append(i == 0 ? "" : ",").append("{\"media_id\":").append(mediaId).append("}");
        }
        json.append("]}");
        String tooMany = json.toString();
        assertThat(codeOf(() -> service().create(ALICE, draft(tooMany, null, null))))
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
        assertThat(codeOf(() -> service().create(ALICE,
                draft("{\"images\":[{\"media_id\":0}]}", null, null))))
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("发布：幂等键命中时不重复落库、不重复写扩散、不吃配额；不带键则每次都是新动态")
    void createIsIdempotentByClientPostId() {
        PlazaService service = service();
        befriend(ALICE, BOB);

        Post first = service.create(ALICE, draft("{\"text\":\"草稿\"}", null, "c-1"));
        int feedRows = feedItems.size();

        Post replay = service.create(ALICE, draft("{\"text\":\"草稿\"}", null, "c-1"));
        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(posts.size()).isEqualTo(1);
        assertThat(feedItems.size()).isEqualTo(feedRows);

        Post withoutKey = service.create(ALICE, draft("{\"text\":\"再发一条\"}", null, null));
        assertThat(withoutKey.getId()).isNotEqualTo(first.getId());

        // 把今天的额度用满，然后：新发帖被拒，而重放同一把幂等键仍然成功
        for (int i = 0; i < properties.getPostDailyQuota() - 2; i++) {
            service.create(ALICE, draft("{\"text\":\"第 " + i + " 条\"}", null, null));
        }
        assertThat(codeOf(() -> service.create(ALICE, draft("{\"text\":\"超额\"}", null, null))))
                .isEqualTo(ErrorCode.DAILY_QUOTA_EXCEEDED);
        assertThat(service.create(ALICE, draft("{\"text\":\"草稿\"}", null, "c-1")).getId())
                .as("重放不该消耗配额，因此配额用满后它仍然成功")
                .isEqualTo(first.getId());
    }

    @Test
    @DisplayName("发布：日配额人类 20 / Agent 50，且按「当天」算（昨天的动态不占今天的额度）")
    void createEnforcesDailyQuota() {
        PlazaService service = service();
        for (int i = 0; i < properties.getPostDailyQuota(); i++) {
            service.create(ALICE, draft("{\"text\":\"第 " + i + " 条\"}", null, null));
        }
        assertThat(codeOf(() -> service.create(ALICE, draft("{\"text\":\"第 21 条\"}", null, null))))
                .isEqualTo(ErrorCode.DAILY_QUOTA_EXCEEDED);

        for (int i = 0; i < properties.getAgentPostDailyQuota(); i++) {
            service.create(BOT, draft("{\"text\":\"bot " + i + "\"}", null, null));
        }
        assertThat(codeOf(() -> service.create(BOT, draft("{\"text\":\"bot 51\"}", null, null))))
                .isEqualTo(ErrorCode.DAILY_QUOTA_EXCEEDED);

        Post yesterday = service.create(CAROL, draft("{\"text\":\"昨天\"}", null, null));
        yesterday.setCreatedAt(LocalDate.now(ZONE).minusDays(1).atTime(9, 0));
        for (int i = 0; i < properties.getPostDailyQuota(); i++) {
            service.create(CAROL, draft("{\"text\":\"今天 " + i + "\"}", null, null));
        }
        assertThat(codeOf(() -> service.create(CAROL, draft("{\"text\":\"又多一条\"}", null, null))))
                .as("昨天的那条不占今天的额度：今天的 20 条应当全部成功")
                .isEqualTo(ErrorCode.DAILY_QUOTA_EXCEEDED);
    }

    // ================================================================ 写扩散

    @Test
    @DisplayName("写扩散：好友各一行收件箱 + 作者自己一行（否则作者看不到自己的动态）")
    void fanoutWritesFriendsAndSelf() {
        befriend(ALICE, BOB);
        Post post = service().create(ALICE, draft("{\"text\":\"你好\"}", null, null));

        assertThat(feedItems.all()).hasSize(2);
        FeedItem toBob = rowOf(BOB);
        assertThat(toBob.getPostId()).isEqualTo(post.getId());
        assertThat(toBob.getAuthorId()).isEqualTo(ALICE);
        assertThat(toBob.getScore())
                .isEqualTo(FeedScores.of(post.getCreatedAt(), ZONE, properties.getFriendBoost(),
                        true, post.getId()));
        assertThat(toBob.getScore() & FeedScores.DEFAULT_FRIEND_BOOST)
                .as("好友位必须被置上（默认 0x80000）").isNotZero();

        FeedItem toSelf = rowOf(ALICE);
        assertThat(toSelf.getScore())
                .as("作者自己那一行不带好友位：在信息流里 is_friend_author=false")
                .isEqualTo(FeedScores.of(post.getCreatedAt(), ZONE, properties.getFriendBoost(),
                        false, post.getId()));

        // 非好友（CAROL）没有行
        assertThat(feedItems.all()).extracting(FeedItem::getOwnerId).containsExactlyInAnyOrder(ALICE, BOB);
    }

    @Test
    @DisplayName("写扩散：好友数超过大 V 阈值只跳过好友那部分（自己的那一行仍然写）")
    void fanoutSkipsCelebrities() {
        properties.setCelebrityThreshold(1);
        befriend(ALICE, BOB);
        befriend(ALICE, CAROL);
        Post post = service().create(ALICE, draft("{\"text\":\"大V的一条\"}", null, null));

        assertThat(feedItems.all()).extracting(FeedItem::getOwnerId)
                .as("超阈值：只留作者自己那一行")
                .containsExactly(ALICE);
        // 作者自己仍然看得到（否则发完就消失）
        assertThat(service().feed(ALICE, 20, null).items())
                .extracting(e -> e.post().getId()).containsExactly(post.getId());
        // 陌生人从公开流里看得到
        assertThat(service().feed(STRANGER, 20, null).items())
                .extracting(e -> e.post().getId()).containsExactly(post.getId());
    }

    @Test
    @DisplayName("写扩散：任务被线程池拒绝时发帖仍然成功（扩散是派生数据，失败不该回滚发帖）")
    void fanoutRejectionDoesNotFailThePost() {
        befriend(ALICE, BOB);
        ExecutorService rejecting = new InlineExecutor() {
            @Override
            public void execute(Runnable command) {
                throw new java.util.concurrent.RejectedExecutionException("替身：队列已满");
            }
        };
        Post post = service(rejecting).create(ALICE, draft("{\"text\":\"还能发出去\"}", null, null));

        assertThat(posts.get(post.getId())).isNotNull();
        assertThat(feedItems.all()).extracting(FeedItem::getOwnerId)
                .as("好友那行被丢弃，只剩作者自己那行（它同步写在发帖事务里）")
                .containsExactly(ALICE);
    }

    // ================================================================ §6.2 信息流

    @Test
    @DisplayName("信息流：好友段在前、公开流段在后；非好友的私密动态既不进好友段也不进公开段")
    void feedPutsFriendsFirstAndHidesPrivate() {
        befriend(ALICE, BOB);
        LocalDateTime now = LocalDateTime.now(ZONE);
        seedPost(BOB, 8001L, Visibility.PUBLIC, "好友的公开动态", now.minusMinutes(30));
        seedPost(BOB, 8002L, Visibility.FRIENDS_ONLY, "好友的私密动态", now.minusMinutes(20));
        seedPost(STRANGER, 8003L, Visibility.PUBLIC, "陌生人的公开动态", now.minusMinutes(10));
        seedPost(STRANGER, 8004L, Visibility.FRIENDS_ONLY, "陌生人的私密动态", now.minusMinutes(5));
        // 好友段来自收件箱：这里手工补上（真实路径由写扩散产生）
        feedItem(BOB, 8001L, now.minusMinutes(30));
        feedItem(BOB, 8002L, now.minusMinutes(20));

        PlazaService.Page<PlazaService.FeedEntry> page = service().feed(ALICE, 20, null);
        assertThat(page.items()).extracting(e -> e.post().getId()).containsExactly(8002L, 8001L, 8003L);
        assertThat(page.items()).extracting(PlazaService.FeedEntry::friendAuthor)
                .containsExactly(true, true, false);
    }

    @Test
    @DisplayName("信息流：仅好友可见的动态对陌生人不可见，作者自己看得到")
    void feedHidesFriendsOnlyFromStrangers() {
        LocalDateTime now = LocalDateTime.now(ZONE);
        seedPost(BOB, 8101L, Visibility.FRIENDS_ONLY, "只给好友", now);
        // 作者自己那一行收件箱（发帖链路会同步写它，这里手工补上）
        feedItems.saveAll(List.of(selfFeedItem(BOB, 8101L, now)));

        assertThat(service().feed(ALICE, 20, null).items()).isEmpty();
        PlazaService.Page<PlazaService.FeedEntry> own = service().feed(BOB, 20, null);
        assertThat(own.items()).extracting(e -> e.post().getId()).containsExactly(8101L);
        assertThat(own.items()).extracting(PlazaService.FeedEntry::friendAuthor)
                .as("自己的动态不是「好友来源」")
                .containsExactly(false);
    }

    @Test
    @DisplayName("信息流：跨页不重不漏（好友段 + 公开段各两页）")
    void feedPagesWithoutGapsOrDuplicates() {
        befriend(ALICE, BOB);
        LocalDateTime now = LocalDateTime.now(ZONE);
        for (int i = 0; i < 3; i++) {
            long postId = 8200L + i;
            seedPost(BOB, postId, Visibility.PUBLIC, "好友 " + i, now.minusMinutes(10 - i));
            feedItem(BOB, postId, now.minusMinutes(10 - i));
        }
        for (int i = 0; i < 3; i++) {
            seedPost(STRANGER, 8300L + i, Visibility.PUBLIC, "陌生人 " + i, now.minusMinutes(5 - i));
        }

        List<Long> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        boolean hasMore = true;
        while (hasMore && pages++ < 10) {
            PlazaService.Page<PlazaService.FeedEntry> page = service().feed(ALICE, 2, cursor);
            page.items().forEach(entry -> seen.add(entry.post().getId()));
            cursor = page.nextCursor();
            hasMore = page.hasMore();
        }
        assertThat(seen).containsExactly(8202L, 8201L, 8200L, 8302L, 8301L, 8300L);
        assertThat(hasMore).as("翻完之后 has_more 必须是 false").isFalse();
    }

    @Test
    @DisplayName("信息流：收件箱行的动态已被删除时静默跳过，且游标仍能推进（不会卡在同一页）")
    void feedSkipsDeletedPosts() {
        befriend(ALICE, BOB);
        LocalDateTime now = LocalDateTime.now(ZONE);
        seedPost(BOB, 8401L, Visibility.PUBLIC, "留着", now.minusMinutes(10));
        feedItem(BOB, 8401L, now.minusMinutes(10));
        // 收件箱里有两行指向已经不存在的动态（删除与清理之间的时间窗）
        feedItem(BOB, 8402L, now.minusMinutes(5));
        feedItem(BOB, 8403L, now.minusMinutes(3));

        PlazaService.Page<PlazaService.FeedEntry> page = service().feed(ALICE, 2, null);
        assertThat(page.items()).extracting(e -> e.post().getId()).containsExactly(8401L);
        assertThat(page.hasMore()).as("删掉的那些不该被当成「还有更多」").isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    // ================================================================ §6.3 某人的动态

    @Test
    @DisplayName("某人的动态：陌生人只看得到 PUBLIC；好友与本人看得到全部")
    void authorPostsRespectVisibility() {
        LocalDateTime now = LocalDateTime.now(ZONE);
        seedPost(BOB, 8501L, Visibility.PUBLIC, "公开", now.minusMinutes(10));
        seedPost(BOB, 8502L, Visibility.FRIENDS_ONLY, "私密", now.minusMinutes(5));

        assertThat(service().authorPosts(STRANGER, BOB, 20, null).items())
                .extracting(e -> e.post().getId()).containsExactly(8501L);
        assertThat(service().authorPosts(BOB, BOB, 20, null).items())
                .extracting(e -> e.post().getId()).containsExactly(8502L, 8501L);

        befriend(BOB, CAROL);
        assertThat(service().authorPosts(CAROL, BOB, 20, null).items())
                .extracting(PlazaService.FeedEntry::friendAuthor).containsExactly(true, true);
    }

    // ================================================================ §6.4 删除

    @Test
    @DisplayName("删除：非作者回 40302；作者删掉之后动态、收件箱、点赞、评论一起清掉")
    void deleteRemovesEverythingAndChecksOwnership() {
        befriend(ALICE, BOB);
        PlazaService service = service();
        Post post = service.create(ALICE, draft("{\"text\":\"要删的\"}", null, null));
        long postId = post.getId();
        service.like(BOB, postId);
        service.addComment(BOB, postId, "评论", null);
        assertThat(feedItems.size()).as("作者自己 + 好友各一行").isEqualTo(2);

        assertThat(codeOf(() -> service.delete(BOB, postId))).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(posts.get(postId)).isNotNull();

        service.delete(ALICE, postId);
        assertThat(posts.get(postId)).isNull();
        assertThat(feedItems.size()).isZero();
        assertThat(likes.size()).isZero();
        assertThat(comments.size()).isZero();
        assertThat(codeOf(() -> service.like(BOB, postId))).isEqualTo(ErrorCode.POST_NOT_FOUND);
    }

    // ================================================================ §6.5 点赞

    @Test
    @DisplayName("点赞：计数 +1；重复点赞回 40907 且计数不变；取消点赞 -1 且幂等（不会变负）")
    void likeIsCountedExactlyOnce() {
        PlazaService service = service();
        Post post = service.create(ALICE, draft("{\"text\":\"来点赞\"}", null, null));
        long postId = post.getId();

        PlazaService.LikeOutcome first = service.like(BOB, postId);
        assertThat(first.likeCount()).isEqualTo(1);
        assertThat(first.liked()).isTrue();

        assertThat(codeOf(() -> service.like(BOB, postId))).isEqualTo(ErrorCode.ALREADY_LIKED);
        assertThat(posts.get(postId).getLikeCount()).isEqualTo(1);

        assertThat(service.unlike(BOB, postId).likeCount()).isZero();
        assertThat(posts.get(postId).getLikeCount()).isZero();

        // 重复取消：幂等，且不会把计数减成负数
        assertThat(service.unlike(BOB, postId).likeCount()).isZero();
        assertThat(posts.get(postId).getLikeCount()).isZero();
    }

    @Test
    @DisplayName("点赞：看不到的动态不能点赞（仅好友可见 + 非好友 → 40302）")
    void likeRequiresVisibility() {
        PlazaService service = service();
        Post post = service.create(BOB, draft("{\"text\":\"只给好友\"}", Visibility.FRIENDS_ONLY, null));
        assertThat(codeOf(() -> service.like(ALICE, post.getId())))
                .isEqualTo(ErrorCode.PERMISSION_DENIED);

        befriend(ALICE, BOB);
        assertThat(service.like(ALICE, post.getId()).likeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("点赞：信息流里的 liked_by_me 反映的是「调用者赞过没有」")
    void feedReportsLikedByMe() {
        PlazaService service = service();
        Post post = service.create(ALICE, draft("{\"text\":\"公开动态\"}", null, null));
        service.like(BOB, post.getId());

        PlazaService.Page<PlazaService.FeedEntry> bobFeed = service.feed(BOB, 20, null);
        assertThat(bobFeed.items()).extracting(PlazaService.FeedEntry::likedByMe).containsExactly(true);

        PlazaService.Page<PlazaService.FeedEntry> carolFeed = service.feed(CAROL, 20, null);
        assertThat(carolFeed.items()).extracting(PlazaService.FeedEntry::likedByMe).containsExactly(false);
    }

    // ================================================================ §6.6 评论

    @Test
    @DisplayName("评论：写入 + 计数 +1；按时间正序下发；回复必须属于同一条动态")
    void commentLifecycle() {
        PlazaService service = service();
        Post post = service.create(ALICE, draft("{\"text\":\"来评论\"}", null, null));
        long postId = post.getId();

        PlazaService.CommentEntry first = service.addComment(BOB, postId, "  第一  ", null);
        assertThat(first.comment().getContent()).isEqualTo("第一");
        assertThat(posts.get(postId).getCommentCount()).isEqualTo(1);

        PlazaService.CommentEntry second = service.addComment(CAROL, postId, "回复第一",
                first.comment().getId());
        assertThat(second.comment().getReplyToCommentId()).isEqualTo(first.comment().getId());
        assertThat(second.author().getId()).isEqualTo(CAROL);
        assertThat(posts.get(postId).getCommentCount()).isEqualTo(2);

        PlazaService.Page<PlazaService.CommentEntry> page = service.listComments(ALICE, postId, 50, null);
        assertThat(page.items()).extracting(e -> e.comment().getId())
                .containsExactly(first.comment().getId(), second.comment().getId());

        // 回复一条别的动态的评论 → 40002
        Post other = service.create(ALICE, draft("{\"text\":\"另一条\"}", null, null));
        assertThat(codeOf(() -> service.addComment(ALICE, other.getId(), "跨动态回复",
                first.comment().getId()))).isEqualTo(ErrorCode.INVALID_PARAMETER);
        // 回复一条不存在的评论 → 40002
        assertThat(codeOf(() -> service.addComment(ALICE, postId, "回复空气", 999_999L)))
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("评论：空内容 40001、超长 40006、看不到的动态 40302")
    void commentValidation() {
        PlazaService service = service();
        Post post = service.create(ALICE, draft("{\"text\":\"来评论\"}", null, null));
        assertThat(codeOf(() -> service.addComment(BOB, post.getId(), "   ", null)))
                .isEqualTo(ErrorCode.MISSING_PARAMETER);
        String longText = "x".repeat(properties.getMaxCommentLength() + 1);
        assertThat(codeOf(() -> service.addComment(BOB, post.getId(), longText, null)))
                .isEqualTo(ErrorCode.CONTENT_TOO_LONG);

        Post secret = service.create(BOB, draft("{\"text\":\"只给好友\"}", Visibility.FRIENDS_ONLY, null));
        assertThat(codeOf(() -> service.addComment(ALICE, secret.getId(), "看不到还评", null)))
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(codeOf(() -> service.listComments(ALICE, secret.getId(), 50, null)))
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("评论：删除权限是「评论作者或动态作者」；第三个人 40302；删完计数 -1")
    void commentDeletePermission() {
        PlazaService service = service();
        Post post = service.create(ALICE, draft("{\"text\":\"来评论\"}", null, null));
        long postId = post.getId();
        long commentId = service.addComment(BOB, postId, "会被删掉的", null).comment().getId();

        assertThat(codeOf(() -> service.deleteComment(CAROL, commentId)))
                .isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(comments.size()).isEqualTo(1);

        // 动态作者可以删别人的评论
        service.deleteComment(ALICE, commentId);
        assertThat(comments.size()).isZero();
        assertThat(posts.get(postId).getCommentCount()).isZero();
        // 删过的评论再删 → 40400（评论确实不存在了）
        assertThat(codeOf(() -> service.deleteComment(ALICE, commentId))).isEqualTo(ErrorCode.NOT_FOUND);

        // 评论作者自己也能删
        long ownComment = service.addComment(BOB, postId, "自己删", null).comment().getId();
        service.deleteComment(BOB, ownComment);
        assertThat(posts.get(postId).getCommentCount()).isZero();
    }

    // ================================================================ 工具

    private PlazaService.PostDraft draft(String contentJson, Visibility visibility, String clientPostId) {
        return new PlazaService.PostDraft(node(contentJson), visibility, clientPostId);
    }

    private static JsonNode node(String json) {
        try {
            return Json.mapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("测试夹具不是合法 JSON: " + json, e);
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** 收件箱里属于某个人的那一行（造不出来时直接失败，而不是返回 null 让后面 NPE）。 */
    private FeedItem rowOf(long ownerId) {
        return feedItems.all().stream()
                .filter(row -> row.getOwnerId() == ownerId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("收件箱里没有 owner=" + ownerId + " 的行"));
    }

    private void befriend(long actorX, long actorY) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        friendships.seed(5_000_000L + actorX + actorY,
                Math.min(actorX, actorY), Math.max(actorX, actorY), actorX,
                FriendshipStatus.ACCEPTED, now.minusDays(1), now.plusDays(6));
    }

    private void seedMedia(long ownerId, long mediaId, int width, int height) {
        Media row = new Media();
        row.setId(mediaId);
        row.setOwnerId(ownerId);
        row.setObjectKey("2026/01/" + mediaId + ".png");
        row.setMime("image/png");
        row.setWidth(width);
        row.setHeight(height);
        row.setSizeBytes(1024L);
        row.setCreatedAt(LocalDateTime.now(ZONE));
        mediaRows.insert(row);
    }

    /** 直接塞一条动态（构造「别人已经发过」这类前置状态，绕开发帖链路）。 */
    private void seedPost(long authorId, long postId, Visibility visibility, String text,
                          LocalDateTime createdAt) {
        Post post = new Post();
        post.setId(postId);
        post.setAuthorId(authorId);
        post.setContent("{\"text\":\"" + text + "\"}");
        post.setVisibility(visibility);
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setCreatedAt(createdAt);
        posts.seed(post);
    }

    /** 直接塞一行收件箱（等价于一次写扩散的产物：收件人是 ALICE、作者是好友）。 */
    private void feedItem(long authorId, long postId, LocalDateTime createdAt) {
        FeedItem item = new FeedItem();
        item.setOwnerId(ALICE);
        item.setScore(FeedScores.of(createdAt, ZONE, properties.getFriendBoost(), true, postId));
        item.setPostId(postId);
        item.setAuthorId(authorId);
        feedItems.saveAll(List.of(item));
    }

    /** 作者自己那一行收件箱（发帖链路在同一个事务里同步写的那行）。 */
    private FeedItem selfFeedItem(long ownerId, long postId, LocalDateTime createdAt) {
        FeedItem item = new FeedItem();
        item.setOwnerId(ownerId);
        item.setScore(FeedScores.of(createdAt, ZONE, properties.getFriendBoost(), false, postId));
        item.setPostId(postId);
        item.setAuthorId(ownerId);
        return item;
    }

    private static ErrorCode codeOf(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        try {
            action.call();
        } catch (TmException e) {
            return e.errorCode();
        } catch (Throwable t) {
            throw new AssertionError("期望 TmException，实际 " + t.getClass().getName(), t);
        }
        throw new AssertionError("期望抛出 TmException，但正常返回了");
    }
}
