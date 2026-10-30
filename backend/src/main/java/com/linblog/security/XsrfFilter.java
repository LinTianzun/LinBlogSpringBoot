package com.linblog.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linblog.common.ApiResult;
import com.linblog.common.ErrorCode;
import com.linblog.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * XSRF 双提交过滤器（§7）：所有非 GET 请求必须满足——
 * ① Origin（若携带）∈ ALLOWED_ORIGINS；② X-XSRF-TOKEN 头 == XSRF-TOKEN Cookie。
 * 有效 Bearer 认证豁免 ②（脚本场景）；GET 无 XSRF Cookie 时种一个。
 * 失败 → 403 + 40300 壳。
 */
public class XsrfFilter extends OncePerRequestFilter {

    private final AppProperties props;
    private final ObjectMapper objectMapper;

    public XsrfFilter(AppProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String method = request.getMethod();
        boolean safeMethod = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method) || "TRACE".equalsIgnoreCase(method);
        if (safeMethod) {
            if (CookieUtil.read(request, CookieUtil.XSRF_COOKIE) == null) {
                response.addHeader("Set-Cookie",
                        new CookieUtil(props).xsrfCookie(UUID.randomUUID().toString()).toString());
            }
            chain.doFilter(request, response);
            return;
        }

        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            List<String> allowed = props.allowedOriginList();
            if (!allowed.contains(origin)) {
                reject(response, "跨站来源被拒绝");
                return;
            }
        }

        if (Boolean.TRUE.equals(request.getAttribute(JwtCookieFilter.ATTR_BEARER))) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader(CookieUtil.XSRF_HEADER);
        String cookie = CookieUtil.read(request, CookieUtil.XSRF_COOKIE);
        if (header == null || header.isBlank() || cookie == null || !header.equals(cookie)) {
            reject(response, "缺少有效的 X-XSRF-TOKEN");
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(ErrorCode.FORBIDDEN.httpStatus().value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResult.fail(ErrorCode.FORBIDDEN, message)));
    }
}
