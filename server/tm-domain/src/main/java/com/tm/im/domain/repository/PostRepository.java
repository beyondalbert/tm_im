package com.tm.im.domain.repository;

import com.tm.im.domain.entity.Post;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 动态（广场）仓储 —— 03-rest-api.md §6 的持久化端口。
 *
 * <p><b>没有「分页查全部」这种入口</b>：广场的读取有且只有三种形态，
 * 每一种都对应一条走索引的语句（见下面各方法）。加一个「随便查」的方法会立刻
 * 变成全表扫描的入口，而它看起来完全正常（本地数据量下毫秒级返回）。
 *
 * <p>时间口径：所有 {@code LocalDateTime} 参数与返回值都是库里的墙上时间，
 * 含义由 {@code tm.time.zone} 定义（见 {@code CoreConfiguration} 里那个 {@link java.time.ZoneId} Bean）。
 */
public interface PostRepository {

    /**
     * 分页游标：{@code (created_at, id)}。
     *
     * <p>两个字段一起才够：{@code created_at} 是 {@code DATETIME(3)}，同一毫秒里
     * 发两条动态完全可能（Agent 批量发帖），只带时间会让第二页的起点落在这一批中间，
     * 结果要么重复下发一批、要么跳过一批。
     */
    record Cursor(LocalDateTime createdAt, long postId) {
    }

    /**
     * 插入一条动态。
     *
     * <p>不返回既有行、也不处理 {@code client_post_id} 冲突：幂等判定属于服务层
     * （它必须先知道「这次到底是新建还是重放」，才能决定要不要写扩散，
     * 见 {@code PlazaService#create}）。这里撞唯一索引就抛出，
     * 让并发重放的那一方去回查——把幂等藏在仓储里会让服务层误以为每次都是新建。
     */
    void insert(Post post);

    Optional<Post> findById(long postId);

    /**
     * 批量取动态（信息流一页一次，而不是每行一次）。
     *
     * <p>返回的<b>可能少于入参</b>：调用方必须自己处理「{@code feed_item} 里有一行、
     * {@code post} 里没有」的情况。这不是分支多余——删除动态与读取信息流是两个并发操作，
     * 而「删掉的动态在别人的收件箱里还躺着」是这个设计的正常中间态（清理是异步的、
     * 且清理失败时靠这里兜住）。跳过它而不是报错：一条已删除的动态不该让整个信息流打不开。
     */
    List<Post> findByIds(Collection<Long> postIds);

    /** 按幂等键查（{@code uk_post_idem} 上的点查）。 */
    Optional<Post> findByClientPostId(long authorId, String clientPostId);

    /** 今日已发多少条（配额判定用；{@code idx_author_time} 上的范围计数）。 */
    int countByAuthorSince(long authorId, LocalDateTime since);

    /** 删除一条动态；返回是否真的删掉了一行（仅用于日志与测试，对外一律 40404 之外都算成功）。 */
    boolean deleteById(long postId);

    /**
     * 原子增减点赞计数（{@code like_count = like_count + delta}）。
     *
     * <p><b>delta 只在「点赞行真的插入/删除成功」时才传</b>（见
     * {@link PostLikeRepository#like} 的返回值）。先查再改会让并发双击把计数加两次，
     * 而不查直接改又会在重复点赞时凭空加一——两种错误都只表现为「数字不太对」，
     * 没有任何报错。
     *
     * <p>用 {@code setSql} 而不是「读出来 +1 再写回」：后者在并发下会丢更新
     * （两个请求都读到 3，都写 4）。这条语句必须在与点赞行同一个事务里执行。
     */
    void addLikeCount(long postId, int delta);

    /** 原子增减评论计数，语义与 {@link #addLikeCount} 相同。 */
    void addCommentCount(long postId, int delta);

    /** §6.3 某人的动态，按时间倒序。{@code cursor} 为 null 表示第一页。 */
    List<Post> pageByAuthor(long authorId, Cursor cursor, int limit);

    /**
     * 广场的「公开流」：全部 {@code visibility=PUBLIC} 的动态，按时间倒序。
     *
     * <p>{@code excludeAuthors} 是「调用者已经通过收件箱拿到动态的那批人」（即他的好友）——
     * 不含它的话，好友的公开动态会在信息流里出现两次（一次来自 {@code feed_item}，
     * 一次来自这里）。排除列表由服务层给出且<b>有上限</b>（见 {@code PlazaService}）：
     * 这个方法的谓词是 {@code NOT IN (...)}，列表长度直接决定 SQL 的长度。
     */
    List<Post> pagePublicExcluding(Collection<Long> excludeAuthors, Cursor cursor, int limit);

    /**
     * 后台的内容列表（M9）：全量、按 {@code id} 倒序分页，{@code beforeId} 为 null 表示第一页。
     *
     * <p><b>它确实推翻了本接口开头那句「没有分页查全部」</b>，所以有义务说清楚为什么。
     * 那句话防的是「用户端出现一条全表扫描的读路径」——不需要任何条件、不需要索引、
     * 在本地数据量下看起来毫秒级返回，上了量之后成为慢查询。而这个方法的调用方是
     * 后台的审核页：它本来就要看「最新的内容」而不管作者是谁，QPS 以「天」计，
     * 而且 {@code ORDER BY id DESC} 走的是主键聚簇索引——慢查询风险在服务端可控
     * （DESIGN §10.6 的 SLO 监控会盯它），而风险由权限隔开的那一侧承担。
     *
     * <p>仍不提供「按任意列搜索」：那才是真的全表扫描入口。
     */
    List<Post> pageForModeration(Long beforeId, int limit);
}
