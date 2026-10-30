package com.linblog.security;

import com.linblog.config.AppProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Cookie 约定（§4.3/§7.2）：
 * - linlog_at   ：访问令牌，HttpOnly + SameSite=Lax   + Path=/           + 15min
 * - linlog_rt   ：刷新令牌，HttpOnly + SameSite=Strict + Path=/api/v1/auth + 7d
 * - XSRF-TOKEN  ：双提交令牌，非 HttpOnly，SameSite=Lax + Path=/           + 7d
 */
@Component
public class CookieUtil {

    public static final String ACCESS_COOKIE = "linlog_at";
    public static final String REFRESH_COOKIE = "linlog_rt";
    public static final String XSRF_COOKIE = "XSRF-TOKEN";
    public static final String XSRF_HEADER = "X-XSRF-TOKEN";
    private static final Duration XSRF_TTL = Duration.ofDays(7);

    private final AppProperties props;

    public CookieUtil(AppProperties props) {
        this.props = props;
    }

    public ResponseCookie accessCookie(String jwt) {
        return ResponseCookie.from(ACCESS_COOKIE, jwt)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(props.jwtAccessTtl())
                .domain(domainOrNull())
                .build();
    }

    public ResponseCookie refreshCookie(String rawToken) {
        return ResponseCookie.from(REFRESH_COOKIE, rawToken)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(Duration.ofDays(props.refreshTtlDays()))
                .domain(domainOrNull())
                .build();
    }

    public ResponseCookie xsrfCookie(String token) {
        return ResponseCookie.from(XSRF_COOKIE, token)
                .httpOnly(false)
                .secure(props.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(XSRF_TTL)
                .domain(domainOrNull())
                .build();
    }

    public ResponseCookie clearAccess() {
        return expired(ACCESS_COOKIE, "/");
    }

    public ResponseCookie clearRefresh() {
        return expired(REFRESH_COOKIE, "/api/v1/auth");
    }

    private ResponseCookie expired(String name, String path) {
        return ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Lax")
                .path(path)
                .maxAge(Duration.ZERO)
                .domain(domainOrNull())
                .build();
    }

    private String domainOrNull() {
        return props.cookieDomain() == null || props.cookieDomain().isBlank() ? null : props.cookieDomain();
    }

    /** 读指定 Cookie 的值；不存在返回 null。 */
    public static String read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
