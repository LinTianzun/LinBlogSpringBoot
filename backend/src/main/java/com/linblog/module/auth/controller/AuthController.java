package com.linblog.module.auth.controller;

import com.linblog.common.ApiResult;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.auth.service.AuthService;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CookieUtil;
import com.linblog.security.CurrentUser;
import com.linblog.security.IpUtil;
import com.linblog.security.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 认证接口（§5.1）：register/login/refresh/reset-request/reset 公开；
 * logout/me/password/change 登录态。限流 IP 维：login/register 共用 RATE_LIMIT_LOGIN，
 * reset-request/reset 共用 RATE_LIMIT_RESET；超限 42900 + Retry-After:60。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** 密码长度 8–72：文档未规定，bcrypt 输入上限 72 字节（汇报偏差）。 */
    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "^[a-zA-Z0-9_]{3,20}$", message = "用户名须为 3-20 位字母、数字或下划线") String username,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 72) String password) {
    }

    public record LoginRequest(@NotBlank String account, @NotBlank String password) {
    }

    public record ResetRequestRequest(@NotBlank @Email String email) {
    }

    public record ResetRequest(@NotBlank String token, @NotBlank @Size(min = 8, max = 72) String password) {
    }

    public record ChangeRequest(@NotBlank String oldPassword, @NotBlank @Size(min = 8, max = 72) String newPassword) {
    }

    private final AuthService authService;
    private final RateLimiter rateLimiter;
    private final CookieUtil cookieUtil;

    public AuthController(AuthService authService, RateLimiter rateLimiter, CookieUtil cookieUtil) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
        this.cookieUtil = cookieUtil;
    }

    @PostMapping("/register")
    public ApiResult<AuthService.UserView> register(@Valid @RequestBody RegisterRequest body,
                                                    HttpServletRequest request) {
        String ip = IpUtil.clientIp(request);
        requireLoginRate(ip);
        return ApiResult.ok(authService.register(body.username(), body.email(), body.password(), ip));
    }

    @PostMapping("/login")
    public ApiResult<AuthService.UserView> login(@Valid @RequestBody LoginRequest body,
                                                 HttpServletRequest request, HttpServletResponse response) {
        String ip = IpUtil.clientIp(request);
        requireLoginRate(ip);
        AuthService.LoginResult result = authService.login(body.account(), body.password(), ip,
                request.getHeader("User-Agent"));
        response.addHeader("Set-Cookie", cookieUtil.accessCookie(result.accessToken()).toString());
        response.addHeader("Set-Cookie", cookieUtil.refreshCookie(result.refreshToken()).toString());
        response.addHeader("Set-Cookie", cookieUtil.xsrfCookie(UUID.randomUUID().toString()).toString());
        return ApiResult.ok(result.user());
    }

    @PostMapping("/refresh")
    public ApiResult<AuthService.UserView> refresh(HttpServletRequest request, HttpServletResponse response) {
        String raw = CookieUtil.read(request, CookieUtil.REFRESH_COOKIE);
        AuthService.LoginResult result = authService.refresh(raw, IpUtil.clientIp(request),
                request.getHeader("User-Agent"));
        response.addHeader("Set-Cookie", cookieUtil.accessCookie(result.accessToken()).toString());
        response.addHeader("Set-Cookie", cookieUtil.refreshCookie(result.refreshToken()).toString());
        return ApiResult.ok(result.user());
    }

    @PostMapping("/logout")
    public ApiResult<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(CookieUtil.read(request, CookieUtil.REFRESH_COOKIE));
        response.addHeader("Set-Cookie", cookieUtil.clearAccess().toString());
        response.addHeader("Set-Cookie", cookieUtil.clearRefresh().toString());
        return ApiResult.ok(null);
    }

    @GetMapping("/me")
    public ApiResult<Map<String, Object>> me() {
        AuthPrincipal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        AuthService.MeResult result = authService.me(principal);
        return ApiResult.ok(Map.of(
                "user", result.user(),
                "permissions", result.permissions(),
                "isSuperAdmin", result.isSuperAdmin()));
    }

    @PostMapping("/password/reset-request")
    public ApiResult<Void> resetRequest(@Valid @RequestBody ResetRequestRequest body, HttpServletRequest request) {
        requireResetRate(IpUtil.clientIp(request));
        authService.resetRequest(body.email(), IpUtil.clientIp(request));
        return ApiResult.ok(null);
    }

    @PostMapping("/password/reset")
    public ApiResult<Void> reset(@Valid @RequestBody ResetRequest body, HttpServletRequest request) {
        String ip = IpUtil.clientIp(request);
        requireResetRate(ip);
        authService.reset(body.token(), body.password(), ip);
        return ApiResult.ok(null);
    }

    @PostMapping("/password/change")
    public ApiResult<Void> change(@Valid @RequestBody ChangeRequest body, HttpServletRequest request,
                                  HttpServletResponse response) {
        AuthPrincipal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        authService.change(principal, body.oldPassword(), body.newPassword(), IpUtil.clientIp(request));
        response.addHeader("Set-Cookie", cookieUtil.clearAccess().toString());
        response.addHeader("Set-Cookie", cookieUtil.clearRefresh().toString());
        return ApiResult.ok(null);
    }

    private void requireLoginRate(String ip) {
        if (!rateLimiter.tryLogin(ip)) {
            throw new BizException(ErrorCode.RATE_LIMITED);
        }
    }

    private void requireResetRate(String ip) {
        if (!rateLimiter.tryReset(ip)) {
            throw new BizException(ErrorCode.RATE_LIMITED);
        }
    }
}
