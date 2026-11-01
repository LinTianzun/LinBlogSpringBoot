package com.linblog.module.stats.controller;

import com.linblog.common.ApiResult;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.stats.dto.SummaryResult;
import com.linblog.module.stats.dto.VisitStatsResult;
import com.linblog.module.stats.service.OverviewService;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台概览接口（§5.8）：summary 汇总 + visit-stats 折线（overview.view）。
 */
@RestController
@RequestMapping("/api/v1/admin/overview")
public class OverviewAdminController {

    private final OverviewService overviewService;

    public OverviewAdminController(OverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping("/summary")
    public ApiResult<SummaryResult> summary() {
        return ApiResult.ok(overviewService.summary(requirePrincipal()));
    }

    @GetMapping("/visit-stats")
    public ApiResult<VisitStatsResult> visitStats(@RequestParam(required = false) String range) {
        return ApiResult.ok(overviewService.visitStats(range, requirePrincipal()));
    }

    private AuthPrincipal requirePrincipal() {
        AuthPrincipal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未登录");
        }
        return principal;
    }
}
