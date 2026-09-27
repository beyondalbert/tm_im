package com.tm.im.api.user.view;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * §6.1 发布动态的请求体。
 *
 * <p>{@code content} 用 {@link JsonNode} 而不是自定义的 record：它的结构由
 * 服务层校验并规范化（见 {@code PlazaService#normalizeContent}），
 * 用一个「字段不完全对得上就 400」的强类型 record 会把校验拆成两处——
 * 而那两处迟早会有一次不一致（比如 record 允许 {@code width} 是字符串）。
 */
public record PostCreateRequest(JsonNode content, String visibility, String clientPostId) {
}
