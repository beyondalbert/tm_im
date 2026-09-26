package com.tm.im.api.user.view;

/**
 * 改角色请求体（03-rest-api.md §4.9 的 {@code PATCH .../members/{actor_id}}）。
 *
 * <p>{@code role} 是包装类型：缺失回 <b>40001</b>（缺参数），而不是被当成 0 或默认值。
 * {@code 0} 与 {@code 4} 这类越界值回 <b>40002</b>——「没传」和「传错了」该给的
 * 客户端动作不同（补字段 vs 改取值），所以不能合并成一个码。
 *
 * <p>类型不匹配（例如 {@code "ADMIN"} 这种字符串）由 Jackson 抛出、被
 * {@code ApiExceptionHandler} 翻成 <b>40000</b>：那是「请求体结构和契约不符」，
 * 与「值非法」是两件事。
 */
public record UpdateMemberRoleRequest(Integer role) {
}
