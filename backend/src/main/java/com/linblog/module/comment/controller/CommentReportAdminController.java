package com.linblog.module.comment.controller;

import com.linblog.common.ApiResult;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.comment.dto.ReportBatchRequest;
import com.linblog.module.comment.dto.ReportHandleRequest;
import com.linblog.module.comment.dto.ReportView;
import com.linblog.module.comment.service.ReportService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端举报工作台（§5.5）：全部端点要求 comment.moderate（服务层校验）。
 * GET ?status=pending 默认；PATCH 单条处理；POST /batch 批量（ids 1–200）。
 */
@RestController
@RequestMapping("/api/v1/admin/comment-reports")
public class CommentReportAdminController {

    private final ReportService reportService;

    public CommentReportAdminController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    public ApiResult<PageResult<ReportView>> page(
            @RequestParam(required = false) String status,
            @Valid PageQuery query) {
        return ApiResult.ok(reportService.page(status, query));
    }

    @PatchMapping("/{id}")
    public ApiResult<Void> handle(@PathVariable long id,
                                  @Valid @RequestBody ReportHandleRequest body) {
        reportService.handle(id, body);
        return ApiResult.ok(null);
    }

    @PostMapping("/batch")
    public ApiResult<ReportService.BatchResult> batch(@RequestBody ReportBatchRequest body) {
        return ApiResult.ok(reportService.batch(body));
    }
}
