package com.linblog.module.comment.controller;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.GlobalExceptionHandler;
import com.linblog.common.PageResult;
import com.linblog.module.comment.dto.ReportBatchRequest;
import com.linblog.module.comment.dto.ReportHandleRequest;
import com.linblog.module.comment.service.ReportService;
import com.linblog.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 举报工作台 Web 层（§5.5）：权限在服务层，这里验证委派与错误码透出。 */
class CommentReportAdminControllerTest {

    private ReportService reportService;
    private MockMvc mockMvc;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @BeforeEach
    void setUp() {
        reportService = Mockito.mock(ReportService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new CommentReportAdminController(reportService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginModerator() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(7L, "moderator", "admin", false, Set.of("comment.moderate")), null, null));
    }

    @Test
    void pageDefaultsToPendingStatus() throws Exception {
        loginModerator();
        Mockito.when(reportService.page(Mockito.isNull(), Mockito.any()))
                .thenReturn(PageResult.of(List.of(), 0));

        mockMvc.perform(get("/api/v1/admin/comment-reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void pagePropagatesForbidden() throws Exception {
        loginModerator();
        Mockito.when(reportService.page(Mockito.anyString(), Mockito.any()))
                .thenThrow(new BizException(ErrorCode.FORBIDDEN, "无评论审核权限"));

        mockMvc.perform(get("/api/v1/admin/comment-reports").param("status", "resolved"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void handleDelegatesAndConflictsOnProcessed() throws Exception {
        loginModerator();
        Mockito.doThrow(new BizException(ErrorCode.CONFLICT, "该举报已处理"))
                .when(reportService).handle(Mockito.eq(3L), Mockito.any(ReportHandleRequest.class));

        mockMvc.perform(patch("/api/v1/admin/comment-reports/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReportHandleRequest("resolved", "已确认"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900))
                .andExpect(jsonPath("$.message").value("该举报已处理"));
    }

    @Test
    void batchReturnsUpdatedAndSkipped() throws Exception {
        loginModerator();
        Mockito.when(reportService.batch(Mockito.any(ReportBatchRequest.class)))
                .thenReturn(new ReportService.BatchResult(2, 1));

        mockMvc.perform(post("/api/v1/admin/comment-reports/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReportBatchRequest(List.of(1L, 2L, 3L), "dismissed"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated").value(2))
                .andExpect(jsonPath("$.data.skipped").value(1));
    }

    @Test
    void anonymousGets40100() throws Exception {
        Mockito.when(reportService.page(Mockito.any(), Mockito.any()))
                .thenThrow(new BizException(ErrorCode.UNAUTHENTICATED, "未登录"));

        mockMvc.perform(get("/api/v1/admin/comment-reports"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }
}
