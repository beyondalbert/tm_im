package com.tm.im.domain.repository;

/**
 * 一个 refresh_token 换回来的会话信息。
 *
 * <p>{@code deviceId} 由客户端在登录时自报（02-auth.md §7 建议「绑定设备指纹」）。
 * 它<b>不参与</b>鉴权判断——本里程碑只是把它带下去，好在日志里回答
 * 「这个账号同时有几个地方在刷 token」。若要真用它做「换设备就拒绝」，
 * 前提是客户端能稳定上报同一个值，而这一点目前没有任何保证。
 */
public record RefreshSession(long actorId, String deviceId) {
}
