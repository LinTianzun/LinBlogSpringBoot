package com.linblog.module.ai.controller;

import com.linblog.common.ApiResult;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.ai.service.AiService;
import com.linblog.module.stats.dto.SummaryResult;
import com.linblog.module.stats.dto.VisitStatsResult;
import com.linblog.module.stats.service.OverviewService;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CurrentUser;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * AI 功能端点（§AI，登录态 + 每用户配额）。铁律：只产出建议文本，
 * 落库由作者确认后走正常保存流程——这些端点永远不直接改业务数据。
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final AiService aiService;
    private final OverviewService overviewService;

    public AiController(AiService aiService, OverviewService overviewService) {
        this.aiService = aiService;
        this.overviewService = overviewService;
    }

    private AuthPrincipal requirePrincipal() {
        AuthPrincipal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        return principal;
    }

    public record SuggestRequest(String title, String content) {
    }

    /** 文章写作建议：简介 + 标签候选。 */
    @PostMapping("/suggest")
    public ApiResult<Map<String, Object>> suggest(@RequestBody SuggestRequest body) {
        AuthPrincipal principal = requirePrincipal();
        return ApiResult.ok(aiService.suggestArticleMeta(principal, body.title(), body.content()));
    }

    /** 举报处置建议：入参为举报上下文（reasonLabel/detail/commentUserName/commentContent/sourceTitle/complaint）。 */
    @PostMapping("/report/review")
    public ApiResult<Map<String, Object>> reviewReport(@RequestBody Map<String, String> body) {
        AuthPrincipal principal = requirePrincipal();
        return ApiResult.ok(aiService.reviewReport(principal, body));
    }

    /** 运营周报：聚合最近 7 天真实统计（总览口径）→ 纯文本周报。 */
    @PostMapping("/report/operations")
    public ApiResult<String> operationsReport() {
        AuthPrincipal principal = requirePrincipal();
        SummaryResult summary = overviewService.summary(principal);
        VisitStatsResult visits = overviewService.visitStats("week", principal);
        long visitTotal = visits.points() == null ? 0
                : visits.points().stream().mapToLong(VisitStatsResult.DailyVisit::count).sum();
        Map<String, Object> stats = Map.of(
                "days", 7,
                "contents", summary.contents(),
                "comments", summary.comments(),
                "messages", summary.unreadMessages(),
                "visits", visitTotal,
                "topPaths", "见后台总览热门列表");
        return ApiResult.ok(aiService.operationsReport(principal, stats));
    }

    /** 提示词试跑（AI 管理页；system.setting 由前端路由守卫保证，端点再查一次）。 */
    @PostMapping("/test-run")
    public ApiResult<Map<String, Object>> testRun(@RequestBody Map<String, Object> body) {
        AuthPrincipal principal = requirePrincipal();
        if (!principal.has(com.linblog.module.permission.PermissionKeys.SYSTEM_SETTING)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权限");
        }
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) body.get("variables");
        String prompt = String.valueOf(body.get("prompt"));
        return ApiResult.ok(aiService.testRun(principal, prompt, vars));
    }

    /** 图库策展 / 私信拟回复：与 suggest 同构的候选建议（后续里程碑扩充）。 */
    @PostMapping("/gallery/curation")
    public ApiResult<Map<String, Object>> galleryCuration() {
        throw new BizException(ErrorCode.NOT_FOUND, "该功能尚未开放");
    }

    /** 占位对齐原版功能清单（生图能力延后）。 */
    @PostMapping("/cover")
    public ApiResult<List<Object>> cover() {
        throw new BizException(ErrorCode.NOT_FOUND, "该功能尚未开放");
    }
}
