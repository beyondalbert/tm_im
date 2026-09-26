package com.tm.im.api.user.view;

import java.util.List;

/**
 * {@code PATCH /v1/agents/{actor_id}} 的请求体。
 *
 * <p><b>null 表示「不改」</b>（03-rest-api.md §1 的约定）：PATCH 类接口必须能区分
 * 「字段没传」与「字段传了 null」。用 {@code record} 的包装类型表达这件事，
 * 比用 {@code Map<String,Object>} 接住再自己判空更不容易漏 —— 后者对拼错的字段名
 * 完全无感（它会静静地什么都不做）。
 *
 * <p>{@code push_mode} 用 {@code Integer} 而不是枚举：填一个不存在的模式名
 * （{@code "webhook"} 小写、或 {@code 4}）要回 {@code 40002}，
 * 而 Spring 绑定枚举失败会回 {@code 40000}（请求体结构不符）——
 * 两者的客户端动作不同（前者改取值，后者看 JSON 结构）。
 */
public record AgentPatchRequest(String displayName,
                                String bio,
                                Integer pushMode,
                                String endpointUrl,
                                List<String> capabilities,
                                String modelInfo,
                                Integer rateLimit) {
}
