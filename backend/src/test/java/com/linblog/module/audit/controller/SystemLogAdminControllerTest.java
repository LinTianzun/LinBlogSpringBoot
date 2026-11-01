package com.linblog.module.audit.controller;

import com.linblog.common.ApiResult;
import com.linblog.common.ErrorCode;
import com.linblog.common.GlobalExceptionHandler;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.audit.dto.SystemLogView;
import com.linblog.module.audit.service.SystemLogQueryService;
import com.linblog.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** §5.8 系统日志接口：分页委派、module/action 筛选与门禁。 */
class SystemLogAdminControllerTest {

    private SystemLogQueryService systemLogQueryService;
    private MockMvc mvc;

    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal(1L, "root", "super_admin", true,
            Set.of("systemLog.view"));

    @BeforeEach
    void setUp() {
        systemLogQueryService = mock(SystemLogQueryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SystemLogAdminController(systemLogQueryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(AuthPrincipal principal) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, null));
    }

    @Test
    void anonymousRejected() throws Exception {
        mvc.perform(request(HttpMethod.GET, "/api/v1/admin/system-logs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.code()));
    }

    @Test
    void pageDelegatesWithFilters() throws Exception {
        loginAs(PRINCIPAL);
        SystemLogView view = new SystemLogView(11L, 7L, "alice", "auth", "register",
                "user", 7L, "新用户注册", "1.1.1.1", LocalDateTime.now());
        when(systemLogQueryService.page(any(PageQuery.class), eq("auth"), eq("register"), eq(PRINCIPAL)))
                .thenReturn(PageResult.of(List.of(view), 1));

        mvc.perform(request(HttpMethod.GET, "/api/v1/admin/system-logs")
                        .param("module", "auth").param("action", "register"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].userName").value("alice"))
                .andExpect(jsonPath("$.data.records[0].module").value("auth"));
    }

    @Test
    void pageWithoutFiltersPassesNull() throws Exception {
        loginAs(PRINCIPAL);
        when(systemLogQueryService.page(any(PageQuery.class), isNull(), isNull(), eq(PRINCIPAL)))
                .thenReturn(PageResult.of(List.of(), 0));

        mvc.perform(request(HttpMethod.GET, "/api/v1/admin/system-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void serviceErrorsMapToShell() throws Exception {
        loginAs(PRINCIPAL);
        when(systemLogQueryService.page(any(PageQuery.class), isNull(), isNull(), eq(PRINCIPAL)))
                .thenThrow(new com.linblog.common.BizException(ErrorCode.FORBIDDEN, "无权访问"));

        mvc.perform(request(HttpMethod.GET, "/api/v1/admin/system-logs"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.code()));
    }
}
