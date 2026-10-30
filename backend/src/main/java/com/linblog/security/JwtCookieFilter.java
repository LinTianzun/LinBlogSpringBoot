package com.linblog.security;

import com.linblog.common.ErrorCode;
import com.linblog.module.permission.service.PermissionService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 认证过滤器（§7.1）：读 Cookie linlog_at（兼容 Authorization: Bearer）→ 验签 →
 * users.status/token_version 校验（30s 缓存）→ 注入 authorities（权限键 + ROLE_xxx）。
 * 过期 → 标记 40101（由 AuthenticationEntryPoint 输出）；无效 → 保持匿名（受保护路径 40100）。
 */
public class JwtCookieFilter extends OncePerRequestFilter {

    /** Bearer 认证成功时置 true，XSRF 过滤器据此豁免（脚本场景）。 */
    public static final String ATTR_BEARER = "linlog.bearerAuth";
    /** 过期标记：入口点据此输出 40101 而非 40100。 */
    public static final String ATTR_EXPIRED = "linlog.tokenExpired";

    private final JwtService jwtService;
    private final UserAuthStateService authStateService;
    private final PermissionService permissionService;

    public JwtCookieFilter(JwtService jwtService, UserAuthStateService authStateService,
                           PermissionService permissionService) {
        this.jwtService = jwtService;
        this.authStateService = authStateService;
        this.permissionService = permissionService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null) {
            try {
                Claims claims = jwtService.parse(token);
                long uid = Long.parseLong(claims.getSubject());
                Integer tv = claims.get("tv", Integer.class);
                String role = claims.get("role", String.class);
                UserAuthStateService.AuthState state = authStateService.load(uid);
                if (state != null && state.enabled() && state.tokenVersion() == (tv == null ? -1 : tv)) {
                    var permissions = permissionService.keysForRole(role);
                    var principal = new AuthPrincipal(uid, state.username(), role, state.roleCode(), permissions);
                    var authentication = new UsernamePasswordAuthenticationToken(
                            principal, null, toAuthorities(permissions, role));
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    if (token.equals(request.getHeader("Authorization") == null ? ""
                            : request.getHeader("Authorization").replaceFirst("(?i)^Bearer\\s+", ""))) {
                        request.setAttribute(ATTR_BEARER, Boolean.TRUE);
                    }
                }
                // 禁用 / 版本不符：保持匿名，受保护路径将得到 40100
            } catch (ExpiredJwtException e) {
                request.setAttribute(ATTR_EXPIRED, Boolean.TRUE);
            } catch (JwtException | IllegalArgumentException e) {
                // 无效令牌：匿名继续
            }
        }
        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String cookieToken = CookieUtil.read(request, CookieUtil.ACCESS_COOKIE);
        if (cookieToken != null && !cookieToken.isBlank()) {
            return cookieToken;
        }
        String header = request.getHeader("Authorization");
        if (header != null && header.matches("(?i)^Bearer\\s+.+")) {
            return header.replaceFirst("(?i)^Bearer\\s+", "").strip();
        }
        return null;
    }

    private static List<SimpleGrantedAuthority> toAuthorities(java.util.Set<String> permissions, String role) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(permissions.size() + 1);
        for (String key : permissions) {
            authorities.add(new SimpleGrantedAuthority(key));
        }
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        return authorities;
    }

    static ErrorCode unauthorizedCode(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(ATTR_EXPIRED)) ? ErrorCode.ACCESS_EXPIRED : ErrorCode.UNAUTHENTICATED;
    }
}
