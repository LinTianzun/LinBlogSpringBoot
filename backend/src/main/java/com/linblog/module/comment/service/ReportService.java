package com.linblog.module.comment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.comment.dto.ReportBatchRequest;
import com.linblog.module.comment.dto.ReportCreateRequest;
import com.linblog.module.comment.dto.ReportHandleRequest;
import com.linblog.module.comment.dto.ReportView;
import com.linblog.module.comment.entity.Comment;
import com.linblog.module.comment.entity.CommentReport;
import com.linblog.module.comment.mapper.CommentMapper;
import com.linblog.module.comment.mapper.CommentReportMapper;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.permission.PermissionKeys;
import com.linblog.module.user.entity.User;
import com.linblog.module.user.mapper.UserMapper;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CurrentUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 评论举报（§5.5）：创建（白名单 reason/自举报 400/重复 40900）+
 * 管理端工作台（分页含评论与作者摘要、单条/批量处理）。状态机 pending→resolved|dismissed。
 */
@Service
public class ReportService {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RESOLVED = "resolved";
    public static final String STATUS_DISMISSED = "dismissed";

    private static final Set<String> REASONS = Set.of(
            "spam", "abuse", "porn", "illegal", "misinformation", "other");
    private static final Set<String> ACTIONS = Set.of(STATUS_RESOLVED, STATUS_DISMISSED);
    private static final int BATCH_MAX = 200;

    private final CommentReportMapper reportMapper;
    private final CommentMapper commentMapper;
    private final UserMapper userMapper;
    private final SystemLogService systemLogService;

    public ReportService(CommentReportMapper reportMapper,
                         CommentMapper commentMapper,
                         UserMapper userMapper,
                         SystemLogService systemLogService) {
        this.reportMapper = reportMapper;
        this.commentMapper = commentMapper;
        this.userMapper = userMapper;
        this.systemLogService = systemLogService;
    }

    /** POST /comments/{id}/report：重复举报 40900；举报本人评论 400（继承原项目口径）。 */
    public void create(long commentId, ReportCreateRequest request) {
        AuthPrincipal principal = requirePrincipal();
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null || !"visible".equals(comment.getStatus())) {
            throw new BizException(ErrorCode.NOT_FOUND, "评论不存在");
        }
        if (comment.getAuthorId() != null && comment.getAuthorId() == principal.uid()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能举报自己的评论");
        }
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (!REASONS.contains(reason)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "举报原因不合法");
        }
        String detail = request.detail() == null ? null : request.detail().trim();
        if (detail != null && detail.length() > 200) {
            throw new BizException(ErrorCode.BAD_REQUEST, "补充说明过长");
        }

        CommentReport report = new CommentReport();
        report.setCommentId(commentId);
        report.setReporterId(principal.uid());
        report.setReason(reason);
        report.setDetail(detail);
        report.setStatus(STATUS_PENDING);
        report.setCreatedAt(LocalDateTime.now());
        try {
            reportMapper.insert(report);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.CONFLICT, "已举报过该评论");
        }
    }

    /** GET /admin/comment-reports?status=pending 默认；分页含评论与作者摘要。 */
    public PageResult<ReportView> page(String status, PageQuery query) {
        requireModerator();
        String state = status == null || status.isBlank() ? STATUS_PENDING : status.trim();
        if (!STATUS_PENDING.equals(state) && !STATUS_RESOLVED.equals(state) && !STATUS_DISMISSED.equals(state)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "举报状态不合法");
        }
        Page<CommentReport> page = new Page<>(query.getPage(), query.getPageSize());
        reportMapper.selectPage(page, new LambdaQueryWrapper<CommentReport>()
                .eq(CommentReport::getStatus, state)
                .orderByAsc(CommentReport::getCreatedAt)
                .orderByAsc(CommentReport::getId));
        return PageResult.of(toViews(page.getRecords()), page.getTotal());
    }

    /** PATCH /admin/comment-reports/{id}：{action: resolved|dismissed, note≤200}。 */
    @Transactional
    public void handle(long id, ReportHandleRequest request) {
        AuthPrincipal principal = requireModerator();
        CommentReport report = reportMapper.selectById(id);
        if (report == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "举报不存在");
        }
        String action = request.action() == null ? "" : request.action().trim();
        if (!ACTIONS.contains(action)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "处理动作不合法");
        }
        if (!STATUS_PENDING.equals(report.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "该举报已处理");
        }
        String note = request.note() == null ? null : request.note().trim();
        if (note != null && note.length() > 200) {
            throw new BizException(ErrorCode.BAD_REQUEST, "备注过长");
        }
        report.setStatus(action);
        report.setHandledBy(principal.uid());
        report.setHandledAt(LocalDateTime.now());
        report.setHandleNote(note);
        reportMapper.updateById(report);
        systemLogService.record(principal.uid(), "report", "handle", "comment_report", id, action, null);
    }

    /** POST /admin/comment-reports/batch：ids 1–200；仅 pending 被处理，其余跳过。 */
    @Transactional
    public BatchResult batch(ReportBatchRequest request) {
        AuthPrincipal principal = requireModerator();
        if (request.ids() == null || request.ids().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "举报 ids 不能为空");
        }
        if (request.ids().size() > BATCH_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, "单次最多处理 200 条举报");
        }
        String action = request.action() == null ? "" : request.action().trim();
        if (!ACTIONS.contains(action)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "处理动作不合法");
        }
        List<CommentReport> pending = reportMapper.selectList(new LambdaQueryWrapper<CommentReport>()
                .in(CommentReport::getId, request.ids())
                .eq(CommentReport::getStatus, STATUS_PENDING));
        LocalDateTime now = LocalDateTime.now();
        for (CommentReport report : pending) {
            report.setStatus(action);
            report.setHandledBy(principal.uid());
            report.setHandledAt(now);
            reportMapper.updateById(report);
        }
        systemLogService.record(principal.uid(), "report", "batch", "comment_report", null,
                action + " x" + pending.size(), null);
        return new BatchResult(pending.size(), request.ids().size() - pending.size());
    }

    public record BatchResult(int updated, int skipped) {
    }

    private List<ReportView> toViews(List<CommentReport> reports) {
        if (reports.isEmpty()) {
            return List.of();
        }
        Set<Long> commentIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        for (CommentReport report : reports) {
            commentIds.add(report.getCommentId());
            if (report.getReporterId() != null) {
                userIds.add(report.getReporterId());
            }
            if (report.getHandledBy() != null) {
                userIds.add(report.getHandledBy());
            }
        }
        Map<Long, Comment> comments = new HashMap<>();
        if (!commentIds.isEmpty()) {
            for (Comment comment : commentMapper.selectBatchIds(commentIds)) {
                comments.put(comment.getId(), comment);
                if (comment.getAuthorId() != null) {
                    userIds.add(comment.getAuthorId());
                }
            }
        }
        Map<Long, String> names = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (User user : userMapper.selectBatchIds(userIds)) {
                names.put(user.getId(), user.getUsername());
            }
        }
        return reports.stream().map(report -> {
            Comment comment = comments.get(report.getCommentId());
            return new ReportView(
                    report.getId(),
                    report.getCommentId(),
                    comment == null ? null : comment.getBody(),
                    comment == null ? null : comment.getAuthorId(),
                    comment == null ? null : names.get(comment.getAuthorId()),
                    comment == null ? null : comment.getStatus(),
                    report.getReporterId(),
                    names.get(report.getReporterId()),
                    report.getReason(),
                    report.getDetail(),
                    report.getStatus(),
                    names.get(report.getHandledBy()),
                    report.getHandleNote(),
                    report.getCreatedAt(),
                    report.getHandledAt());
        }).toList();
    }

    private AuthPrincipal requireModerator() {
        AuthPrincipal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未登录");
        }
        if (!principal.has(PermissionKeys.COMMENT_MODERATE)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无评论审核权限");
        }
        return principal;
    }

    private AuthPrincipal requirePrincipal() {
        AuthPrincipal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未登录");
        }
        return principal;
    }
}
