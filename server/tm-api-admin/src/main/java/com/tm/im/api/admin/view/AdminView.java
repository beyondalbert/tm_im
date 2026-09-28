package com.tm.im.api.admin.view;

import java.time.Instant;

/**
 * 后台账号的对外形状。
 *
 * <p>没有 {@code passwordHash}，也没有 {@code failedAttempts}/{@code lockedUntil}：
 * 前者是绝不能被读出来的东西，后两者是「运维自己的操作状态」，
 * 让它们出现在响应里只会诱使客户端做「失败几次了」的判断，而那个判断在服务端。
 */
public record AdminView(long adminId, String username, String displayName, int role, int status,
                        Instant createdAt, Instant lastLoginAt) {
}
