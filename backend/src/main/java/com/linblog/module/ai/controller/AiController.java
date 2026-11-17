package com.linblog.module.ai.controller;

import com.linblog.common.ApiResult;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.ai.service.AiImageService;
import com.linblog.module.ai.service.AiService;
import com.linblog.module.message.service.MessageService;
import com.linblog.module.stats.dto.SummaryResult;
import com.linblog.module.stats.dto.VisitStatsResult;
import com.linblog.module.stats.service.OverviewService;
import com.linblog.module.user.entity.User;
import com.linblog.module.user.mapper.UserMapper;
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
    private final AiImageService imageService;
    private final OverviewService overviewService;
    private final MessageService messageService;
    private final UserMapper userMapper;

    public AiController(AiService aiService, AiImageService imageService, OverviewService overviewService,
                        MessageService messageService, UserMapper userMapper) {
        this.aiService = aiService;
        this.imageService = imageService;
        this.overviewService = overviewService;
        this.messageService = messageService;
        this.userMapper = userMapper;
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

    /** 生图候选：{token, dataUri, prompt, modelName}——确认采用后才落 media（AI 图片域）。 */
    @PostMapping("/cover")
    public ApiResult<Map<String, Object>> cover(@RequestBody Map<String, String> body) {
        AuthPrincipal principal = requirePrincipal();
        return ApiResult.ok(imageService.generateCover(
                principal, body.get("title"), body.get("introduce"), body.get("content")));
    }

    /** 一次性确认采用 AI 封面：token 消费 → 落 media → 返回封面引用（mediaId/storageKey/url）。 */
    @PostMapping("/cover/confirm")
    public ApiResult<Map<String, Object>> confirmCover(@RequestBody Map<String, String> body) {
        AuthPrincipal principal = requirePrincipal();
        return ApiResult.ok(imageService.confirmCover(principal, body.get("token")));
    }

    /**
     * 私信拟回复：2 条候选话术。上下文只取会话双方可见的消息
     * （messages() 内做参与者校验，非双方 40400），AI 不改动任何消息。
     */
    @PostMapping("/message/reply")
    public ApiResult<Map<String, Object>> messageReply(@RequestBody Map<String, Object> body) {
        AuthPrincipal principal = requirePrincipal();
        long conversationId = body.get("conversationId") instanceof Number n ? n.longValue() : 0;
        if (conversationId <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "conversationId 不能为空");
        }
        String tone = body.get("tone") instanceof String t && !t.isBlank() ? t : null;

        MessageService.MessagePage page = messageService.messages(principal, conversationId, null);
        List<MessageService.DirectMessageView> recent = page.records() == null ? List.of()
                : page.records().subList(Math.max(0, page.records().size() - 10), page.records().size());
        if (recent.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "会话还没有消息，无从拟回复");
        }
        StringBuilder history = new StringBuilder();
        Long peerId = null;
        for (MessageService.DirectMessageView m : recent) {
            boolean mine = m.senderId() == principal.uid();
            history.append(mine ? "我" : "对方").append("：").append(m.body()).append('\n');
            if (!mine) peerId = m.senderId();
        }
        String peerName = "对方";
        if (peerId != null) {
            User peer = userMapper.selectById(peerId);
            if (peer != null) {
                peerName = peer.getDisplayName();
            }
        }
        return ApiResult.ok(Map.of(
                "replies", aiService.suggestMessageReplies(principal, peerName, history.toString(), tone)));
    }
}