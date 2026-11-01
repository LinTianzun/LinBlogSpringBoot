package com.linblog.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 提取：优先 X-Forwarded-For 首段（第一跳代理追加的原始客户端），
 * 否则 remoteAddr。审计与限流共用。
 */
public final class IpUtil {

    private IpUtil() {
    }

    public static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            for (String segment : xff.split(",")) {
                String ip = segment.strip();
                if (!ip.isEmpty()) {
                    return ip;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
