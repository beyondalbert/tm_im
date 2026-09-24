package com.tm.im.domain.support;

/**
 * 单聊会话去重键与好友无序对的归一化。
 *
 * <p>数据库层面有两处依赖「无序对」的约定：
 * <ul>
 *   <li>{@code conversation.pair_key}：格式 {@code min_max}，唯一索引保证 A 发起与 B 发起命中同一会话；</li>
 *   <li>{@code friendship(actor_a, actor_b)}：约定 {@code actor_a < actor_b}，消除方向。</li>
 * </ul>
 * 这两处若由调用方各自保证顺序，早晚会有人传反，而症状是「查不到」
 * ——返回「不是好友」，而不是报错。表现为「明明是好友却发不出消息」，
 * 排查成本极高。所以归一化收拢在这里，仓储实现必须调用它。
 */
public final class PairKeys {

    private PairKeys() {
    }

    /** 两个 Actor 的无序对，返回 {@code [min, max]}。 */
    public static long[] ordered(long actorX, long actorY) {
        return actorX <= actorY ? new long[]{actorX, actorY} : new long[]{actorY, actorX};
    }

    public static long min(long actorX, long actorY) {
        return Math.min(actorX, actorY);
    }

    public static long max(long actorX, long actorY) {
        return Math.max(actorX, actorY);
    }

    /**
     * 单聊会话去重键：{@code min_max}。
     *
     * <p>与 DDL 中 {@code pair_key VARCHAR(80)} 的注释一致。
     * 两个 Snowflake ID 最长各 19 位数字，加下划线共 39 字符，留有余量。
     */
    public static String directConversationKey(long actorX, long actorY) {
        return min(actorX, actorY) + "_" + max(actorX, actorY);
    }

    /** 自己和自己不能建单聊会话，也不能加好友。 */
    public static boolean isSelf(long actorX, long actorY) {
        return actorX == actorY;
    }
}
