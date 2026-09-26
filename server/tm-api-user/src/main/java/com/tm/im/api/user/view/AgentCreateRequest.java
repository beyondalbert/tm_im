package com.tm.im.api.user.view;

import java.util.List;

/**
 * {@code POST /v1/agents} 的请求体（02-auth.md §3.1）。
 *
 * <p>{@code push_mode} 用 {@code Integer}：文档里写的是数字（{@code "push_mode": 1}），
 * 而取值非法（{@code 4}）与字段缺失（{@code null}）要回<b>不同的</b>错误码
 * （40002 / 40001）——用枚举接的话两者都会变成绑定失败，只剩一个码可用。
 */
public record AgentCreateRequest(String handle,
                                 String displayName,
                                 String bio,
                                 Integer pushMode,
                                 String endpointUrl,
                                 List<String> capabilities,
                                 String modelInfo,
                                 Integer rateLimit) {
}
