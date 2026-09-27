package com.tm.im.domain.support;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 信息流排序分（DESIGN §11.3）—— {@code feed_item.score} 的唯一计算点。
 *
 * <pre>
 * score = (发帖时间戳 / 1000) &lt;&lt; 20        // 高位：时间序（秒级）
 *         | (isFriend ? 0x80000 : 0)       // 中位：好友加权
 *         | (tieBreaker &amp; 0xFFFFF)         // 低位：防碰撞
 * </pre>
 *
 * <p><b>为什么必须收在一个函数里</b>：score 是 {@code feed_item} 主键的一部分
 * （{@code (owner_id, score, post_id)}），而分页游标直接携带它。
 * 两处各算一遍、或某个客户端版本按自己的理解重排，结果都是
 * 「翻页时丢掉一部分动态」——而丢的不是报错，是静默少了几条。
 *
 * <h2>关于「好友优先」的位次（一处被文档说矛盾的地方）</h2>
 *
 * <p>DESIGN §11.3 把好友位放在<b>时间位之下</b>（bit 19），于是它只能
 * 「同一秒内好友靠前」；而 03-rest-api.md §6.2 与 01-concepts.md §8 承诺的是
 * 「你好友的动态优先展示、非好友的公开动态排在后面」（全局优先）。两者不可能同时成立。
 *
 * <p>本实现的选择是：<b>公式按 DESIGN 原样实现（它是内部约定，游标格式依赖它），
 * 全局优先由读取路径实现</b>——信息流分两段读（好友收件箱 → 公开流），
 * 见 {@code PlazaService#feed}。这样：
 * <ul>
 *   <li>对外效果与两份集成文档一致（好友段整体在前）；</li>
 *   <li>{@code score} 仍是「时间为主、同秒内好友靠前」，游标里存的就是它，
 *       公式一旦改动，所有在途游标立刻失效（{@code PageCursors} 的版本号即为此而设）。</li>
 * </ul>
 * 数据库里的 score 因此只服务好友收件箱那一段；公开流按 {@code created_at} 排序
 * （走 {@code idx_visibility_time}），两段各自有序、由游标里的 {@code band} 拼接。
 */
public final class FeedScores {

    /**
     * 好友加权的<b>默认</b>分值：bit 19，恰好落在时间位（bit 20 起）之下、
     * 防碰撞位（bit 0-19）之上。实际取值来自 {@code tm.feed.friend-boost}
     * （默认值就是它），{@link #of} 会校验它没越界。
     */
    public static final long DEFAULT_FRIEND_BOOST = 0x80000L;

    /** 防碰撞位掩码：低 20 位。也是好友加权的上界（超过它就会溢进时间位）。 */
    public static final long TIE_MASK = 0xFFFFFL;

    private FeedScores() {
    }

    /**
     * 算一个 score。
     *
     * @param createdAt    发帖时间（库里的墙上时间）
     * @param zone         库里时间的时区（{@code tm.time.zone}）——把时间换成
     *                     epoch 秒必须用它，用 UTC 会整体平移，排序本身不变、
     *                     但「同一秒」的边界会错位，于是同秒内好友加权时而生效时而不生效
     * @param friendBoost  好友加权值（{@code tm.feed.friend-boost}）；必须落在
     *                     {@code [0, }{@value #TIE_MASK}{@code ]} 内，否则抛异常
     * @param friendAuthor 收件人是否与作者是好友
     * @param postId       动态 id，低 20 位作为同秒内的稳定次序
     */
    public static long of(LocalDateTime createdAt, ZoneId zone, long friendBoost,
                          boolean friendAuthor, long postId) {
        if (friendBoost < 0 || friendBoost > TIE_MASK) {
            // 越界会让好友加权溢进高位的时间位：「时间倒序」这个主要排序规则
            // 会被一个配置值盖掉，而表现是「信息流顶部永远是一年前的某条好友动态」。
            // 它必须在第一次发帖时就爆，而不是等运维自己看出来。
            throw new IllegalArgumentException("tm.feed.friend-boost 必须落在 [0, " + TIE_MASK
                    + "] 内（当前 " + friendBoost + "）：越界会溢进时间位");
        }
        long epochSecond = createdAt.atZone(zone).toEpochSecond();
        if (epochSecond < 0) {
            // 1970 之前的时间在这儿没有意义（Snowflake、DATETIME(3) 都是现代时间），
            // 而负数右移会把高位全部变成 1，得到一个巨大的 score —— 那样这条动态
            // 会永久钉在信息流最前面，且看不出原因。
            throw new IllegalArgumentException("发帖时间早于 epoch: " + createdAt);
        }
        return (epochSecond << 20) | (friendAuthor ? friendBoost : 0L) | (postId & TIE_MASK);
    }
}
