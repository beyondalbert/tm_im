package com.tm.im.domain.enums;

/**
 * 带显式落库码值的枚举。
 *
 * <p>为什么不用 Java 的 {@code name()} 或 {@code ordinal()} 直接落库：
 * <ul>
 *   <li>{@code name()}：常量一重命名（HUMAN → PERSON），历史数据立刻变成非法值；</li>
 *   <li>{@code ordinal()}：在枚举中间插入一个新常量，会让其后所有常量的含义整体错位。
 *       这是最难排查的一类数据损坏——库里存的是数字，肉眼完全看不出错。</li>
 * </ul>
 * 显式码值把「落库表示」与「Java 命名」解耦，两者可以各自演进。
 *
 * <p>全部实现类由 {@code EnumCodeTest} 自动扫描并校验码值往返。
 */
public interface CodedEnum {

    int code();
}
