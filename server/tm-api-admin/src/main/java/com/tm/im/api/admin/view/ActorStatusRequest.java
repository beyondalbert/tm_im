package com.tm.im.api.admin.view;

/**
 * 改参与者状态（封禁 / 解封）。
 *
 * @param status 1=ACTIVE 2=SUSPENDED（数字码，与库里的取值一致）
 * @param reason 可选的原因，进审计行的 {@code detail}。它不是装饰：
 *               「为什么封这个人」是半年后唯一还记得的问题。
 */
public record ActorStatusRequest(Integer status, String reason) {
}
