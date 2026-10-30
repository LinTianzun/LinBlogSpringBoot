package com.linblog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linblog.common.ApiResult;
import com.linblog.common.ErrorCode;
import com.linblog.module.audit.filter.RequestAuditFilter;
import com.linblog.module.audit.mapper.RequestAuditMapper;
import com.linblog.module.permission.service.PermissionService;
import com.linblog.security.JwtCookieFilter;
import com.linblog.security.JwtService;
import com.linblog.security.UserAuthStateService;
import com.linblog.security.XsrfFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 无状态安全配置（§7.2/§7.3）：链序 audit → jwt → xsrf；
 * 401/403 一律输出 JSON 壳（40100/40101/40300）。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** §5 各表「公开」行 + 健康检查与 API 文档 + /error（Boot 错误转发）。 */
    public static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/password/reset-request",
            "/api/v1/auth/password/reset",
            "/api/v1/portal/**",
            "/api/v1/labels",
            "/api/v1/media/**",
            "/api/v1/views",
            "/api/v1/guestbook",
            "/api/v1/sitemap.xml",
            "/api/v1/robots.txt",
            "/actuator/health",
            "/actuator/health/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/error"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AppProperties props, ObjectMapper objectMapper,
                                                   JwtService jwtService, UserAuthStateService authStateService,
                                                   PermissionService permissionService,
                                                   RequestAuditMapper requestAuditMapper) throws Exception {
        RequestAuditFilter auditFilter = new RequestAuditFilter(requestAuditMapper);
        JwtCookieFilter jwtFilter = new JwtCookieFilter(jwtService, authStateService, permissionService);
        XsrfFilter xsrfFilter = new XsrfFilter(props, objectMapper);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource(props)))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(objectMapper, request, response, JwtCookieFilter.unauthorizedCode(request)))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(objectMapper, request, response, ErrorCode.FORBIDDEN)))
                .addFilterBefore(auditFilter, LogoutFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(xsrfFilter, JwtCookieFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(props.allowedOriginList());
        config.setAllowedMethods(List.of("*"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-Request-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    private static void writeError(ObjectMapper objectMapper, HttpServletRequest request,
                                   HttpServletResponse response, ErrorCode errorCode) {
        try {
            response.setStatus(errorCode.httpStatus().value());
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json;charset=UTF-8");
            if (errorCode == ErrorCode.RATE_LIMITED) {
                response.setHeader("Retry-After", "60");
            }
            response.getWriter().write(
                    objectMapper.writeValueAsString(ApiResult.fail(errorCode)));
        } catch (Exception e) {
            // 响应已提交或连接断开：无法再写错误壳
        }
    }
}
