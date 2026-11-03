package com.linblog.module.comment.controller;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.GlobalExceptionHandler;
import com.linblog.module.comment.dto.CommentCreateRequest;
import com.linblog.module.comment.dto.CommentThread;
import com.linblog.module.comment.dto.CommentView;
import com.linblog.module.comment.dto.PraiseResult;
import com.linblog.module.comment.dto.ReportCreateRequest;
import com.linblog.module.comment.service.CommentService;
import com.linblog.module.comment.service.ReportService;
import com.linblog.module.message.service.SsePushService;
import com.linblog.module.permission.PermissionKeys;
import com.linblog.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统一评论接口（§5.5）Web 层：权限/业务判定都在服务层，这里验证路由绑定、
 * 权限码透出、公开读取放行、举报时向 comment.moderate 推送 report.new。
 */
class CommentControllerTest {

    private CommentService commentService;
    private ReportService reportService;
    private SsePushService ssePushService;
    private MockMvc mockMvc;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @BeforeEach
    void setUp() {
        commentService = Mockito.mock(CommentService.class);
        reportService = Mockito.mock(ReportService.class);
        ssePushService = Mockito.mock(SsePushService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CommentController(commentService, reportService, ssePushService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private CommentView sampleView() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 4, 12, 0);
        return new CommentView(1L, "article", 9L, 5L, "alice", null, null, null, null,
                "很赞", 3L, false, now, null);
    }

    private void login(long uid, String username, String role, Set<String> perms) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(uid, username, role, false, perms), null, null));
    }

    @Test
    void threadIsPublicAndPassesNullPrincipalWhenAnonymous() throws Exception {
        Mockito.when(commentService.thread(Mockito.eq("article"), Mockito.eq(9L), Mockito.isNull()))
                .thenReturn(new CommentThread(List.of(sampleView()), List.of()));

        mockMvc.perform(get("/api/v1/portal/article/9/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.rootComments[0].body").value("很赞"));

        Mockito.verify(commentService).thread("article", 9L, null);
    }

    @Test
    void threadPassesPrincipalWhenLoggedIn() throws Exception {
        login(5L, "alice", "viewer", Set.of());
        Mockito.when(commentService.thread(Mockito.eq("diary"), Mockito.eq(3L), Mockito.any()))
                .thenReturn(new CommentThread(List.of(), List.of()));

        mockMvc.perform(get("/api/v1/portal/diary/3/comments"))
                .andExpect(status().isOk());

        ArgumentCaptor<AuthPrincipal> captor = ArgumentCaptor.forClass(AuthPrincipal.class);
        Mockito.verify(commentService).thread(Mockito.eq("diary"), Mockito.eq(3L), captor.capture());
        assertEquals(5L, captor.getValue().uid());
    }

    @Test
    void createDelegatesAndReturnsView() throws Exception {
        login(5L, "alice", "viewer", Set.of());
        Mockito.when(commentService.create(Mockito.any(CommentCreateRequest.class))).thenReturn(sampleView());

        mockMvc.perform(post("/api/v1/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CommentCreateRequest("article", 9L, null, null, "很赞"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.body").value("很赞"));

        Mockito.verify(commentService).create(Mockito.any(CommentCreateRequest.class));
    }

    @Test
    void createPropagatesBizException() throws Exception {
        login(5L, "alice", "viewer", Set.of());
        Mockito.when(commentService.create(Mockito.any(CommentCreateRequest.class)))
                .thenThrow(new BizException(ErrorCode.FORBIDDEN, "帖子不可评论"));

        mockMvc.perform(post("/api/v1/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CommentCreateRequest("article", 9L, null, null, "hi"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300))
                .andExpect(jsonPath("$.message").value("帖子不可评论"));
    }

    @Test
    void deleteDelegatesAndReturnsOk() throws Exception {
        login(5L, "alice", "viewer", Set.of());

        mockMvc.perform(delete("/api/v1/comments/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        Mockito.verify(commentService).delete(7L);
    }

    @Test
    void praiseReturnsToggleResult() throws Exception {
        login(5L, "alice", "viewer", Set.of());
        Mockito.when(commentService.praise(7L)).thenReturn(new PraiseResult(true, 4L));

        mockMvc.perform(post("/api/v1/comments/7/praise"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.count").value(4));
    }

    @Test
    void reportPushesReportNewToModeratorPermission() throws Exception {
        login(5L, "alice", "viewer", Set.of());

        mockMvc.perform(post("/api/v1/comments/7/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReportCreateRequest("spam", "广告"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        Mockito.verify(reportService).create(Mockito.eq(7L), Mockito.any(ReportCreateRequest.class));
        // §5.8 report.new：推给 comment.moderate 持有者
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(ssePushService).pushToPermission(
                Mockito.eq(PermissionKeys.COMMENT_MODERATE),
                Mockito.eq(SsePushService.EVENT_REPORT_NEW),
                payload.capture());
    }

    @Test
    void reportWithNullReasonSendsEmptyString() throws Exception {
        login(5L, "alice", "viewer", Set.of());

        mockMvc.perform(post("/api/v1/comments/8/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReportCreateRequest(null, null))))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> payload =
                ArgumentCaptor.forClass(java.util.Map.class);
        Mockito.verify(ssePushService).pushToPermission(
                Mockito.eq(PermissionKeys.COMMENT_MODERATE),
                Mockito.eq(SsePushService.EVENT_REPORT_NEW),
                payload.capture());
        assertEquals(8L, payload.getValue().get("commentId"));
        assertEquals("", payload.getValue().get("reason"));
    }

    @Test
    void reportPermissionDeniedPropagates() throws Exception {
        login(5L, "alice", "viewer", Set.of());
        Mockito.doThrow(new BizException(ErrorCode.FORBIDDEN, "无权举报"))
                .when(reportService).create(Mockito.eq(7L), Mockito.any(ReportCreateRequest.class));

        mockMvc.perform(post("/api/v1/comments/7/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReportCreateRequest("abuse", "x"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        Mockito.verifyNoInteractions(ssePushService);
    }
}
