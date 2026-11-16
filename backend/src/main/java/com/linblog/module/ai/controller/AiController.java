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

    /** 写作辅助：润色/精简/扩写/续写正文片段，返回纯文本候选（作者确认后才写编辑器）。 */
    @PostMapping("/writing")
    public ApiResult<Map<String, Object>> rewrite(@RequestBody Map<String, String> body) {
        AuthPrincipal principal = requirePrincipal();
        return ApiResult.ok(aiService.rewriteArticleText(
                principal, body.get("action"), body.get("title"), body.get("text")));
    }

    /** 图库策展建议：候选标题/简介/标签（作者逐项确认后写入）。 */
    @PostMapping("/gallery/curation")
    public ApiResult<Map<String, Object>> galleryCuration(@RequestBody Map<String, Object> body) {
        AuthPrincipal principal = requirePrincipal();
        int photoCount = body.get("photoCount") instanceof Number n ? n.intValue() : 0;
        return ApiResult.ok(aiService.suggestGalleryCuration(
                principal,
                String.valueOf(body.getOrDefault("title", "")),
                String.valueOf(body.getOrDefault("introduce", "")),
                String.valueOf(body.getOrDefault("theme", "")),
                photoCount));
    }

    /** 生图（AI 封面/配图）：随图片生成能力开放（未移植）。 */
    @PostMapping("/cover")
    public ApiResult<Void> cover() {
        throw new BizException(ErrorCode.NOT_FOUND, "该功能尚未开放");
    }
}