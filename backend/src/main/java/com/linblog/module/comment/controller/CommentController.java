package com.linblog.module.comment.controller;

import com.linblog.common.ApiResult;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.comment.dto.CommentCreateRequest;
import com.linblog.module.comment.dto.CommentThread;
import com.linblog.module.comment.dto.CommentView;
import com.linblog.module.comment.dto.PraiseResult;
import com.linblog.module.comment.dto.ReportCreateRequest;
import com.linblog.module.comment.service.CommentService;
import com.linblog.module.comment.service.ReportService;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一评论接口（§5.5）：门户两段式读取（公开）+ 写操作（登录）。
 * 管理端举报工作台见 CommentReportAdminController。
 */
@RestController
public class CommentController {

    private final CommentService commentService;
    private final ReportService reportService;

    public CommentController(CommentService commentService, ReportService reportService) {
        this.commentService = commentService;
        this.reportService = reportService;
    }

    /** GET /portal/{targetType}/{id}/comments：公开；仅 status=visible；登录时附带 liked。 */
    @GetMapping("/api/v1/portal/{targetType}/{id}/comments")
    public ApiResult<CommentThread> comments(@PathVariable String targetType, @PathVariable long id) {
        return ApiResult.ok(commentService.thread(targetType, id, CurrentUser.get()));
    }

    @PostMapping("/api/v1/comments")
    public ApiResult<CommentView> create(@Valid @RequestBody CommentCreateRequest body) {
        return ApiResult.ok(commentService.create(body));
    }

    /** 作者 或 comment.moderate；一级删连带回复逻辑删 + 计数回退（事务）。 */
    @DeleteMapping("/api/v1/comments/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        commentService.delete(id);
        return ApiResult.ok(null);
    }

    @PostMapping("/api/v1/comments/{id}/praise")
    public ApiResult<PraiseResult> praise(@PathVariable long id) {
        return ApiResult.ok(commentService.praise(id));
    }

    @PostMapping("/api/v1/comments/{id}/report")
    public ApiResult<Void> report(@PathVariable long id,
                                  @Valid @RequestBody ReportCreateRequest body) {
        reportService.create(id, body);
        return ApiResult.ok(null);
    }
}
