package com.tm.im.core.plaza;

/**
 * 「删掉一条动态（连带收件箱 / 点赞 / 评论）」这个动作的端口。
 *
 * <p><b>为什么是一个接口而不是直接用 {@link PlazaService}</b>：调用方是后台的审核动作
 * （{@code AdminService}），而它只用得到这一个方法——把整个 {@code PlazaService}
 * 作为依赖，等于把「发帖 / 信息流 / 点赞 / 评论」全部拉进后台服务的依赖图里，
 * 而其中绝大多数路径后台一行都不会走。端口只有一个方法，这件事就写在类型上。
 *
 * <p>接口定义在 plaza 包，实现也在 plaza（{@code PlazaService}）：方向是
 * 「后台依赖 plaza 的能力」，而不是「plaza 知道后台的存在」。反过来定义成
 * {@code core.admin.PostDeleter} 再由 PlazaService 实现，会让两个包互相依赖。
 */
public interface PostDeletionPort {

    /**
     * 后台删帖：跳过「只有作者能删」的判断，其余（级联清理）与作者删帖完全一致。
     *
     * @throws com.tm.im.common.error.TmException {@code 40404} 动态不存在
     */
    void deleteAsAdmin(long postId);
}
