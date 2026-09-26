package com.tm.im.api.user.view;

import java.util.List;

/**
 * 游标分页的统一外壳（03-rest-api.md §1.5）。
 *
 * <p>泛型而不是每个列表各写一个只有名字不同的 record：外壳的字段名与语义
 * （{@code items}/{@code next_cursor}/{@code has_more}）在 §1.5 里是全局约定，
 * 每个接口复制一份的话，某一次「顺手改了一个字段名」不会有任何东西报错。
 *
 * <p>{@code next_cursor} 为 {@code null} 就真的没有更多了；{@code has_more} 是精确值
 * （不是「这一页满了吗」的猜测），两者永远一致。
 */
public record SocialPageView<T>(List<T> items, String nextCursor, boolean hasMore) {
}
