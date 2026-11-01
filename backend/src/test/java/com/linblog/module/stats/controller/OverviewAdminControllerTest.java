package com.linblog.module.stats.controller;

import com.linblog.common.ApiResult;
import com.linblog.common.ErrorCode;
import com.linblog.common.GlobalExceptionHandler;
import com.linblog.module.stats.dto.SummaryResult;
import com.linblog.module.stats.dto.VisitStatsResult;
import com.linblog.module.stats.service.OverviewService;
import com.linblog.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** §5.8 概览接口：summary/visit-stats 委派与门禁。 */
class OverviewAdminControllerTest {

    private OverviewService overviewService;
    private MockMvc mvc;

    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal(1L, "root", "super_admin", true,
            Set.of("overview.view"));

    @BeforeEach
    void setUp() {
        overviewService = mock(OverviewService.class);
        mvc = MockMvcBuilders.standaloneSetup(new OverviewAdminController(overviewService))
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
        mvc.perform(request(HttpMethod.GET, "/api/v1/admin/overview/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.code()));
    }

    @Test
    void summaryDelegates() throws Exception {
        loginAs(PRINCIPAL);
        when(overviewService.summary(PRINCIPAL))
                .thenReturn(new SummaryResult(12L, 34L, 5L, 7L, 99L, 199L));

        mvc.perform(request(HttpMethod.GET, "/api/v1/admin/overview/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.contents").value(12))
                .andExpect(jsonPath("$.data.unreadMessages").value(5))
                .andExpect(jsonPath("$.data.viewsWeek").value(199));
    }

    @Test
    void visitStatsDelegatesRange() throws Exception {
        loginAs(PRINCIPAL);
        VisitStatsResult result = new VisitStatsResult("moon", List.of(
                new VisitStatsResult.DailyVisit(LocalDate.now(), 3L)));
        when(overviewService.visitStats(eq("moon"), eq(PRINCIPAL))).thenReturn(result);

        mvc.perform(request(HttpMethod.GET, "/api/v1/admin/overview/visit-stats")
                        .param("range", "moon"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.range").value("moon"))
                .andExpect(jsonPath("$.data.points[0].count").value(3));
    }

    @Test
    void visitStatsBlankRangePassesNull() throws Exception {
        loginAs(PRINCIPAL);
        when(overviewService.visitStats(isNull(), eq(PRINCIPAL)))
                .thenReturn(new VisitStatsResult("week", List.of()));

        mvc.perform(request(HttpMethod.GET, "/api/v1/admin/overview/visit-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.range").value("week"));

        verify(overviewService).visitStats(isNull(), eq(PRINCIPAL));
    }

    @Test
    void serviceErrorsMapToShell() throws Exception {
        loginAs(PRINCIPAL);
        when(overviewService.summary(PRINCIPAL))
                .thenThrow(new com.linblog.common.BizException(ErrorCode.FORBIDDEN, "无权访问"));

        mvc.perform(request(HttpMethod.GET, "/api/v1/admin/overview/summary"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.code()));
    }
}
