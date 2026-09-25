package com.tm.im.api.user.auth;

/**
 * 设备标识的取值与规范化。
 *
 * <p><b>为什么单独抽出来</b>：它有两个来源——鉴权路径（
 * {@link CurrentActorArgumentResolver} 从请求头取）与登录/注册路径
 * （控制器直接读同一个头，那时还没有身份可解）。若两处各写一遍
 * 「strip + 截断」，规则就会漂移，而漂移的表现是「同一个客户端在
 * 登录接口上报的设备名和在其他接口上不一样」——一个几乎不会被注意到、
 * 但会让「看我的登录设备」这类功能变得不可信的区别。
 */
public final class DeviceId {

    /** 请求头名。与 03-rest-api.md §1.2 的约定一并维护。 */
    public static final String HEADER = "X-TM-Device-Id";

    /**
     * 长度上限。它会被写进 Redis 的值里、可能进日志，
     * 不设限就等于让客户端能往这两个地方灌任意长度的字符串。
     */
    static final int MAX_LENGTH = 128;

    private DeviceId() {
    }

    /**
     * @param rawHeader 原始请求头值，可为 null
     * @return 规范化后的设备标识；未上报时返回 {@code null}
     *         （而不是空串——「没上报」与「上报了空值」将来若要区分，现在就得留着这个差别）
     *
     * <p>超长时<b>截断而不是拒绝</b>：设备标识是「便于诊断」的信息，
     * 为它让一个登录请求失败不成比例；而拒绝还会让客户端拿同一个坏值反复重试。
     */
    public static String from(String rawHeader) {
        if (rawHeader == null) {
            return null;
        }
        String trimmed = rawHeader.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_LENGTH ? trimmed.substring(0, MAX_LENGTH) : trimmed;
    }
}
