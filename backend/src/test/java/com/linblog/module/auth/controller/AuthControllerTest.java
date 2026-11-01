package com.linblog.module.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.GlobalExceptionHandler;
import com.linblog.module.auth.service.AuthService;
import com.linblog.security.CookieUtil;
import com.linblog.security.RateLimiter;
import com.linblog.support.TestAppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 认证 Web 层（standalone MockMvc）：响应壳结构、@Valid 校验、限流 429+Retry-After、业务码映射。 */
class AuthControllerTest {

    private AuthService authService;
    private RateLimiter rateLimiter;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        authService = Mockito.mock(AuthService.class);
        rateLimiter = Mockito.mock(RateLimiter.class);
        CookieUtil cookieUtil = new CookieUtil(TestAppProperties.defaults());
        AuthController controller = new AuthController(authService, rateLimiter, cookieUtil);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void registerValidationFailureReturns40001WithFieldErrors() throws Exception {
        String body = objectMapper.writeValueAsString(
                new AuthController.RegisterRequest("x", "not-an-email", "short"));
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("字段校验失败"))
                .andExpect(jsonPath("$.data.fieldErrors").isArray())
                .andExpect(jsonPath("$.data.fieldErrors.length()").value(3));
    }

    @Test
    void registerSuccessReturnsShellWithCode0() throws Exception {
        when(rateLimiter.tryLogin(anyString())).thenReturn(true);
        when(authService.register(eq("neo"), eq("neo@linlog.com"), anyString(), anyString()))
                .thenReturn(new AuthService.UserView(1L, "neo", "neo", "neo@linlog.com", "viewer", 1, null));

        String body = objectMapper.writeValueAsString(
                new AuthController.RegisterRequest("neo", "neo@linlog.com", "Password8"));
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.username").value("neo"));
    }

    @Test
    void loginSuccessSetsThreeCookies() throws Exception {
        when(rateLimiter.tryLogin(anyString())).thenReturn(true);
        when(authService.login(eq("alice"), anyString(), anyString(), any()))
                .thenReturn(new AuthService.LoginResult(9L, "jwt-token", "raw-refresh",
                        new AuthService.UserView(9L, "alice", "alice", "a@b.c", "editor", 1, null)));

        String body = objectMapper.writeValueAsString(new AuthController.LoginRequest("alice", "Password8"));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().stringValues("Set-Cookie",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("linlog_at=jwt-token"))))
                .andExpect(header().stringValues("Set-Cookie",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("linlog_rt=raw-refresh"))))
                .andExpect(header().stringValues("Set-Cookie",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("XSRF-TOKEN="))))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void loginRateLimited429WithRetryAfter() throws Exception {
        when(rateLimiter.tryLogin(anyString())).thenReturn(false);
        String body = objectMapper.writeValueAsString(new AuthController.LoginRequest("a", "b"));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value(42900));
    }

    @Test
    void loginDisabledAccountMapsTo40300() throws Exception {
        when(rateLimiter.tryLogin(anyString())).thenReturn(true);
        when(authService.login(anyString(), anyString(), anyString(), any()))
                .thenThrow(new BizException(ErrorCode.FORBIDDEN, "账号已禁用"));

        String body = objectMapper.writeValueAsString(new AuthController.LoginRequest("alice", "Password8"));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300))
                .andExpect(jsonPath("$.message").value("账号已禁用"));
    }

    @Test
    void unreadableBodyMapsTo40000() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void resetRequestRateLimitedUsesResetBucket() throws Exception {
        when(rateLimiter.tryReset(anyString())).thenReturn(false);
        String body = objectMapper.writeValueAsString(new AuthController.ResetRequestRequest("a@b.c"));
        mockMvc.perform(post("/api/v1/auth/password/reset-request")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42900));
    }

    @Test
    void meWithoutAuthenticationMapsTo40100() throws Exception {
        // standalone MockMvc 无过滤器 → SecurityContext 为空 → CurrentUser.get() == null
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }
}
