package com.linblog.module.comment.service;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.comment.dto.ReportBatchRequest;
import com.linblog.module.comment.dto.ReportCreateRequest;
import com.linblog.module.comment.dto.ReportHandleRequest;
import com.linblog.module.comment.dto.ReportView;
import com.linblog.module.comment.entity.Comment;
import com.linblog.module.comment.entity.CommentReport;
import com.linblog.module.comment.mapper.CommentMapper;
import com.linblog.module.comment.mapper.CommentReportMapper;
import com.linblog.module.permission.PermissionKeys;
import com.linblog.module.user.entity.User;
import com.linblog.module.user.mapper.UserMapper;
import com.linblog.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 评论举报（§5.5）：白名单 reason、自举报 400、重复 40900、审核工作台分页与单条/批量处理。 */
class ReportServiceTest {

    private CommentReportMapper reportMapper;
    private CommentMapper commentMapper;
    private UserMapper userMapper;
    private SystemLogService systemLogService;
    private ReportService service;

    private AuthPrincipal moderator;

    @BeforeEach
    void setUp() {
        registerTableInfo(CommentReport.class);
        reportMapper = mock(CommentReportMapper.class);
        commentMapper = mock(CommentMapper.class);
        userMapper = mock(UserMapper.class);
        systemLogService = mock(SystemLogService.class);
        service = new ReportService(reportMapper, commentMapper, userMapper, systemLogService);
        moderator = new AuthPrincipal(7L, "ed", "editor", false,
                Set.of(PermissionKeys.COMMENT_MODERATE));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void registerTableInfo(Class<?> entity) {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), entity);
    }

    private void login(AuthPrincipal principal) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private Comment comment(long id, Long authorId) {
        Comment c = new Comment();
        c.setId(id);
        c.setTargetType(CommentService.DB_CONTENT);
        c.setTargetId(11L);
        c.setAuthorId(authorId);
        c.setBody("被举报的评论" + id);
        c.setStatus("visible");
        return c;
    }

    private CommentReport report(long id, long commentId, long reporterId, String status, Long handledBy) {
        CommentReport r = new CommentReport();
        r.setId(id);
        r.setCommentId(commentId);
        r.setReporterId(reporterId);
        r.setReason("spam");
        r.setDetail("广告引流");
        r.setStatus(status);
        r.setHandledBy(handledBy);
        r.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        return r;
    }

    private User user(long id) {
        User u = new User();
        u.setId(id);
        u.setUsername("user" + id);
        return u;
    }

    private String bizMessage(Runnable run) {
        BizException e = assertThrows(BizException.class, run::run);
        return e.getMessage();
    }

    @Test
    void createRequiresLogin() {
        BizException e = assertThrows(BizException.class,
                () -> service.create(500L, new ReportCreateRequest("spam", null)));
        assertEquals(ErrorCode.UNAUTHENTICATED.code(), e.getErrorCode().code());
        assertEquals("未登录", e.getMessage());
    }

    @Test
    void createRejectsMissingOrInvisibleComment() {
        login(moderator);
        when(commentMapper.selectById(500L)).thenReturn(null);
        assertEquals("评论不存在", bizMessage(() -> service.create(500L, new ReportCreateRequest("spam", null))));

        Comment hidden = comment(500L, 2L);
        hidden.setStatus("deleted");
        when(commentMapper.selectById(500L)).thenReturn(hidden);
        assertEquals("评论不存在", bizMessage(() -> service.create(500L, new ReportCreateRequest("spam", null))));
    }

    @Test
    void createRejectsSelfReportButAllowsNullAuthor() {
        login(moderator);
        when(commentMapper.selectById(500L)).thenReturn(comment(500L, 7L));
        assertEquals("不能举报自己的评论", bizMessage(
                () -> service.create(500L, new ReportCreateRequest("spam", null))));

        when(commentMapper.selectById(501L)).thenReturn(comment(501L, null));
        service.create(501L, new ReportCreateRequest("spam", null));
        verify(reportMapper).insert(any(CommentReport.class));
    }

    @Test
    void createValidatesReasonAndDetail() {
        login(moderator);
        when(commentMapper.selectById(500L)).thenReturn(comment(500L, 2L));
        assertEquals("举报原因不合法", bizMessage(
                () -> service.create(500L, new ReportCreateRequest("ad", null))));
        assertEquals("举报原因不合法", bizMessage(
                () -> service.create(500L, new ReportCreateRequest(null, null))));
        assertEquals("补充说明过长", bizMessage(
                () -> service.create(500L, new ReportCreateRequest("spam", "x".repeat(201)))));
        verify(reportMapper, never()).insert(any(CommentReport.class));

        // 合法（reason/detail 均会 trim）
        service.create(500L, new ReportCreateRequest("  spam  ", "  引流  "));
        ArgumentCaptor<CommentReport> captor = ArgumentCaptor.forClass(CommentReport.class);
        verify(reportMapper).insert(captor.capture());
        CommentReport saved = captor.getValue();
        assertEquals(500L, saved.getCommentId());
        assertEquals(7L, saved.getReporterId());
        assertEquals("spam", saved.getReason());
        assertEquals("引流", saved.getDetail());
        assertEquals(ReportService.STATUS_PENDING, saved.getStatus());
        assertNotNull(saved.getCreatedAt());
    }

    @Test
    void createDuplicateReportConflicts() {
        login(moderator);
        when(commentMapper.selectById(500L)).thenReturn(comment(500L, 2L));
        doThrow(new DuplicateKeyException("uk_comment_report")).when(reportMapper).insert(any(CommentReport.class));
        BizException e = assertThrows(BizException.class,
                () -> service.create(500L, new ReportCreateRequest("spam", null)));
        assertEquals(ErrorCode.CONFLICT.code(), e.getErrorCode().code());
        assertEquals("已举报过该评论", e.getMessage());
    }

    @Test
    void pageRequiresModerator() {
        BizException anonymous = assertThrows(BizException.class,
                () -> service.page("pending", new PageQuery()));
        assertEquals(ErrorCode.UNAUTHENTICATED.code(), anonymous.getErrorCode().code());

        AuthPrincipal nobody = new AuthPrincipal(9L, "guest", "viewer", false, Set.of());
        login(nobody);
        BizException forbidden = assertThrows(BizException.class,
                () -> service.page("pending", new PageQuery()));
        assertEquals(ErrorCode.FORBIDDEN.code(), forbidden.getErrorCode().code());
        assertEquals("无评论审核权限", forbidden.getMessage());
    }

    @Test
    void pageValidatesStatus() {
        login(moderator);
        assertEquals("举报状态不合法", bizMessage(() -> service.page("weird", new PageQuery())));
        verify(reportMapper, never()).selectPage(any(), any());
    }

    @Test
    void pageReturnsEnrichedViewsAndToleratesDeletedComment() {
        login(moderator);
        CommentReport handled = report(1L, 500L, 3L, ReportService.STATUS_RESOLVED, 7L);
        handled.setHandledAt(LocalDateTime.of(2026, 1, 2, 0, 0));
        handled.setHandleNote("已删评");
        CommentReport dangling = report(2L, 999L, 2L, ReportService.STATUS_PENDING, null);
        when(reportMapper.selectPage(any(), any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<CommentReport> page = inv.getArgument(0);
            page.setRecords(List.of(handled, dangling));
            page.setTotal(2);
            return page;
        });
        when(commentMapper.selectBatchIds(any())).thenReturn(List.of(comment(500L, 2L)));
        when(userMapper.selectBatchIds(any())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            return List.of(user(2L), user(3L), user(7L)).stream()
                    .filter(u -> ids.contains(u.getId()))
                    .collect(Collectors.toList());
        });

        PageResult<ReportView> result = service.page("  ", new PageQuery());

        assertEquals(2, result.total());
        ReportView first = result.records().get(0);
        assertEquals(1L, first.id());
        assertEquals(500L, first.commentId());
        assertEquals("被举报的评论500", first.commentBody());
        assertEquals(2L, first.commentAuthorId());
        assertEquals("user2", first.commentAuthorName());
        assertEquals("visible", first.commentStatus());
        assertEquals("user3", first.reporterName());
        assertEquals(ReportService.STATUS_RESOLVED, first.status());
        assertEquals("user7", first.handledByName());
        assertEquals("已删评", first.handleNote());

        // 评论已被物理清理 → 摘要字段 null 安全
        ReportView second = result.records().get(1);
        assertNull(second.commentBody());
        assertNull(second.commentAuthorName());
        assertNull(second.handledByName());
        assertEquals("user2", second.reporterName());
        assertEquals(ReportService.STATUS_PENDING, second.status());
    }

    @Test
    void handleGuardsAndAppliesResolution() {
        login(moderator);
        when(reportMapper.selectById(1L)).thenReturn(null);
        assertEquals("举报不存在", bizMessage(() -> service.handle(1L, new ReportHandleRequest("resolved", null))));

        CommentReport pending = report(1L, 500L, 2L, ReportService.STATUS_PENDING, null);
        when(reportMapper.selectById(1L)).thenReturn(pending);
        assertEquals("处理动作不合法", bizMessage(() -> service.handle(1L, new ReportHandleRequest("close", null))));
        assertEquals("备注过长", bizMessage(() -> service.handle(1L, new ReportHandleRequest("resolved", "x".repeat(201)))));

        pending.setStatus(ReportService.STATUS_RESOLVED);
        assertEquals("该举报已处理", bizMessage(() -> service.handle(1L, new ReportHandleRequest("resolved", null))));
        assertEquals(ReportService.STATUS_RESOLVED, pending.getStatus());
        pending.setStatus(ReportService.STATUS_PENDING);

        service.handle(1L, new ReportHandleRequest("  resolved  ", "  已删评  "));
        assertEquals(ReportService.STATUS_RESOLVED, pending.getStatus());
        assertEquals(7L, pending.getHandledBy());
        assertNotNull(pending.getHandledAt());
        assertEquals("已删评", pending.getHandleNote());
        verify(reportMapper).updateById(pending);
        verify(systemLogService).record(7L, "report", "handle", "comment_report", 1L, "resolved", null);
    }

    @Test
    void batchValidatesIdsAndAction() {
        login(moderator);
        assertEquals("举报 ids 不能为空", bizMessage(
                () -> service.batch(new ReportBatchRequest(List.of(), "resolved"))));
        assertEquals("举报 ids 不能为空", bizMessage(
                () -> service.batch(new ReportBatchRequest(null, "resolved"))));
        assertEquals("单次最多处理 200 条举报", bizMessage(
                () -> service.batch(new ReportBatchRequest(
                        java.util.stream.LongStream.rangeClosed(1, 201).boxed().collect(Collectors.toList()),
                        "resolved"))));
        assertEquals("处理动作不合法", bizMessage(
                () -> service.batch(new ReportBatchRequest(List.of(1L), "close"))));
        verify(reportMapper, never()).selectList(any());
    }

    @Test
    void batchUpdatesOnlyPendingAndReportsSkipped() {
        login(moderator);
        CommentReport r1 = report(1L, 500L, 2L, ReportService.STATUS_PENDING, null);
        CommentReport r2 = report(2L, 501L, 3L, ReportService.STATUS_PENDING, null);
        when(reportMapper.selectList(any())).thenReturn(List.of(r1, r2));

        ReportService.BatchResult result = service.batch(new ReportBatchRequest(List.of(1L, 2L, 3L), "dismissed"));

        assertEquals(2, result.updated());
        assertEquals(1, result.skipped());
        assertEquals(ReportService.STATUS_DISMISSED, r1.getStatus());
        assertEquals(ReportService.STATUS_DISMISSED, r2.getStatus());
        assertEquals(7L, r1.getHandledBy());
        assertNotNull(r2.getHandledAt());
        verify(reportMapper).updateById(r1);
        verify(reportMapper).updateById(r2);
        verify(systemLogService).record(7L, "report", "batch", "comment_report", null, "dismissed x2", null);
    }
}
