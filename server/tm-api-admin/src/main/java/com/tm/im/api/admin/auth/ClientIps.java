package com.tm.im.api.admin.auth;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/**
 * 取客户端 IP —— 审计行需要它，而 {@code getRemoteAddr()} 在反向代理之后
 * 拿到的是代理的地址。
 *
 * <p><b>头是按顺序取的，而不是「有就用」</b>：这些头都可以由客户端伪造，
 * 所以在没有代理的部署里必须只信 {@code getRemoteAddr()}。
 * 这里的策略是「先看 {@code X-Forwarded-For} 的第一段（最靠近客户端的那一段），
 * 没有则回落到 socket 地址」——它是<b>观测值而不是判据</b>：
 * 没有任何鉴权或限流逻辑读这个值，所以伪造它的后果只是审计行里记了一个假 IP，
 * 而那件事在「谁改了什么」这个问题上本来也只排在第二位。
 *
 * <p>反过来说，如果哪天有人拿它做「IP 白名单」这类判据，就必须先把它换成
 * 「只信任已知代理链」的实现（否则伪造一个头就能绕过白名单）。
 * 这条注释存在的意义就是让那次改动是一次明示的决策。
 */
public final class ClientIps {

    private static final Pattern IP = Pattern.compile("^[0-9a-fA-F:.]{3,45}$");

    private ClientIps() {
    }

    public static String of(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (IP.matcher(first).matches()) {
                return first;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && IP.matcher(realIp.trim()).matches()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
