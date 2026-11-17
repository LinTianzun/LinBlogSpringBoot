package com.linblog.module.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.ai.entity.AiModel;
import com.linblog.module.ai.entity.AiPromptBinding;
import com.linblog.module.ai.entity.AiPromptTemplate;
import com.linblog.module.ai.entity.AiSetting;
import com.linblog.module.ai.entity.AiUsageLog;
import com.linblog.module.ai.mapper.AiModelMapper;
import com.linblog.module.ai.mapper.AiPromptBindingMapper;
import com.linblog.module.ai.mapper.AiPromptTemplateMapper;
import com.linblog.module.ai.mapper.AiSettingMapper;
import com.linblog.module.ai.mapper.AiUsageLogMapper;
import com.linblog.security.AuthPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 编排（§AI）：模型解析 → 提示词解析（模板/内置回退）→ 配额 → 调用 → 用量记录。
 * 铁律与原版一致：AI 只产出建议文本，任何落库都由作者确认后走正常保存流程。
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final AiModelMapper modelMapper;
    private final AiSettingMapper settingMapper;
    private final AiUsageLogMapper usageLogMapper;
    private final AiPromptTemplateMapper promptTemplateMapper;
    private final AiPromptBindingMapper promptBindingMapper;
    private final AiClientService client;
    private final ObjectMapper objectMapper;

    public AiService(AiModelMapper modelMapper, AiSettingMapper settingMapper,
                     AiUsageLogMapper usageLogMapper, AiPromptTemplateMapper promptTemplateMapper,
                     AiPromptBindingMapper promptBindingMapper, AiClientService client,
                     ObjectMapper objectMapper) {
        this.modelMapper = modelMapper;
        this.settingMapper = settingMapper;
        this.usageLogMapper = usageLogMapper;
        this.promptTemplateMapper = promptTemplateMapper;
        this.promptBindingMapper = promptBindingMapper;
        this.client = client;
        this.objectMapper = objectMapper;
    }

    /* ==================== 模型与策略 ==================== */

    public AiSetting settings() {
        AiSetting setting = settingMapper.selectById(1);
        if (setting == null) {
            setting = new AiSetting();
            setting.setId(1);
            setting.setQuotaEnabled(true);
            setting.setDailyLimit(20);
            setting.setMonthlyLimit(100);
            setting.setSuperAdminUnlimited(true);
            settingMapper.insert(setting);
        }
        return setting;
    }

    /** 解析本次调用使用的模型：策略指定 → 默认模型 → 任一启用模型；无可用 → 50300。 */
    public AiModel resolveModel() {
        AiSetting setting = settings();
        if (setting.getChatModelId() != null) {
            AiModel picked = modelMapper.selectById(setting.getChatModelId());
            if (picked != null && Boolean.TRUE.equals(picked.getEnabled())) {
                return picked;
            }
        }
        AiModel fallback = modelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getEnabled, true)
                        .orderByDesc(AiModel::getIsDefault)
                        .orderByAsc(AiModel::getId)
                        .last("LIMIT 1")).stream().findFirst().orElse(null);
        if (fallback == null) {
            throw new BizException(ErrorCode.INTERNAL, "AI 功能未配置，请先在 AI 管理中启用接入模型");
        }
        return fallback;
    }

    /* ==================== 配额与用量 ==================== */

    private void checkQuota(AuthPrincipal principal, String feature) {
        AiSetting setting = settings();
        if (!Boolean.TRUE.equals(setting.getQuotaEnabled())) {
            return;
        }
        if (Boolean.TRUE.equals(setting.getSuperAdminUnlimited()) && principal.superAdmin()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        long daily = usageLogMapper.selectCount(new LambdaQueryWrapper<AiUsageLog>()
                .eq(AiUsageLog::getUserId, principal.uid())
                .eq(AiUsageLog::getFeature, feature)
                .ge(AiUsageLog::getCreatedAt, now.minusDays(1)));
        if (setting.getDailyLimit() != null && setting.getDailyLimit() > 0
                && daily >= setting.getDailyLimit()) {
            throw new BizException(ErrorCode.RATE_LIMITED, "今日 AI 调用次数已达上限（" + setting.getDailyLimit() + " 次/天）");
        }
        long monthly = usageLogMapper.selectCount(new LambdaQueryWrapper<AiUsageLog>()
                .eq(AiUsageLog::getUserId, principal.uid())
                .ge(AiUsageLog::getCreatedAt, now.minusDays(30)));
        if (setting.getMonthlyLimit() != null && setting.getMonthlyLimit() > 0
                && monthly >= setting.getMonthlyLimit()) {
            throw new BizException(ErrorCode.RATE_LIMITED, "本月 AI 调用次数已达上限（" + setting.getMonthlyLimit() + " 次/月）");
        }
    }

    private void logUsage(AuthPrincipal principal, String feature, AiModel model, String status,
                          String detail, long durationMs, int promptTokens, int completionTokens, int outputChars) {
        try {
            AiUsageLog row = new AiUsageLog();
            row.setUserId(principal.uid());
            row.setFeature(feature);
            row.setModelName(model == null ? null : model.getChatModel());
            row.setStatus(status);
            row.setDetail(detail);
            row.setDurationMs((int) Math.min(durationMs, Integer.MAX_VALUE));
            row.setPromptTokens(promptTokens);
            row.setCompletionTokens(completionTokens);
            row.setOutputChars(outputChars);
            usageLogMapper.insert(row);
        } catch (RuntimeException e) {
            log.warn("AI 用量记录失败（不影响主响应）: {}", e.getMessage());
        }
    }

    /** 生图调用的用量记录（无 token 数，只计耗时与状态）。 */
    public void logImageCall(AuthPrincipal principal, String feature, AiModel model, String status,
                             String detail, long durationMs) {
        logUsage(principal, feature, model, status, detail, durationMs, 0, 0, 0);
    }

    /** 用量摘要（admin 页）：按状态/功能聚合最近 30 天。 */
    public Map<String, Object> usageSummary() {
        LocalDateTime from = LocalDateTime.now().minusDays(30);
        List<AiUsageLog> rows = usageLogMapper.selectList(new LambdaQueryWrapper<AiUsageLog>()
                .ge(AiUsageLog::getCreatedAt, from));
        long total = rows.size();
        long success = rows.stream().filter(r -> "success".equals(r.getStatus())).count();
        double avgDuration = rows.stream().mapToInt(AiUsageLog::getDurationMs).average().orElse(0);
        Map<String, Long> byFeature = new LinkedHashMap<>();
        for (AiUsageLog row : rows) {
            byFeature.merge(row.getFeature(), 1L, Long::sum);
        }
        return Map.of(
                "total", total,
                "success", success,
                "successRate", total == 0 ? 0 : Math.round(success * 1000.0 / total) / 10.0,
                "avgDurationMs", Math.round(avgDuration),
                "byFeature", byFeature);
    }

    /* ==================== 提示词 ==================== */

    /** 解析功能提示词：绑定模板（启用）→ 内置；同时返回来源标记。 */
    public ResolvedPrompt resolvePrompt(String featureKey) {
        AiPromptBinding binding = promptBindingMapper.selectById(featureKey);
        if (binding != null) {
            AiPromptTemplate template = promptTemplateMapper.selectOne(new LambdaQueryWrapper<AiPromptTemplate>()
                    .eq(AiPromptTemplate::getTemplateKey, binding.getTemplateKey())
                    .eq(AiPromptTemplate::getEnabled, true)
                    .last("LIMIT 1"));
            if (template != null) {
                return new ResolvedPrompt(template.getContent(), template.getName(), false);
            }
        }
        AiBuiltInPrompts.BuiltinPrompt builtin = AiBuiltInPrompts.ALL.get(featureKey);
        if (builtin == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未知的 AI 功能：" + featureKey);
        }
        return new ResolvedPrompt(builtin.content(), builtin.name(), true);
    }

    public record ResolvedPrompt(String content, String name, boolean builtin) {
    }

    /** {{变量}} 渲染（未提供的变量替换为空串）。 */
    public static String render(String template, Map<String, String> vars) {
        String out = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            out = out.replace("{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : entry.getValue());
        }
        return out.replaceAll("\\{\\{[a-zA-Z]+}}", "");
    }

    /** 从模型输出提取 JSON（剥 Markdown 代码围栏）。 */
    private JsonNode parseJsonOutput(String text) {
        try {
            String cleaned = text.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("```\\s*$", "").trim();
            }
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            }
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "AI 输出不是有效的 JSON，请重试或调整模板");
        }
    }

    /* ==================== 功能（只建议，不落库） ==================== */

    private record CallOutcome(String text, AiModel model, int promptTokens, int completionTokens, long durationMs) {
    }

    /** 配额 → 模型 → 调用 → 用量；拒绝记 denied，失败记 failed，均不影响可读错误回传。 */
    private CallOutcome callModel(AuthPrincipal principal, String feature, String systemPrompt,
                                  String userPrompt, boolean json) {
        long startedAt = System.currentTimeMillis();
        AiModel model = null;
        try {
            checkQuota(principal, feature);
            model = resolveModel();
            AiClientService.ChatResult result = client.chatCompletion(model, systemPrompt, userPrompt, json);
            long duration = System.currentTimeMillis() - startedAt;
            logUsage(principal, feature, model, "success", null, duration,
                    result.promptTokens(), result.completionTokens(), result.text().length());
            return new CallOutcome(result.text(), model, result.promptTokens(), result.completionTokens(), duration);
        } catch (BizException e) {
            String status = e.getErrorCode() == ErrorCode.RATE_LIMITED ? "denied" : "failed";
            logUsage(principal, feature, model, status, e.getMessage(), System.currentTimeMillis() - startedAt, 0, 0, 0);
            throw e;
        }
    }

    /** 文章写作建议：{summary, labels[]}。 */
    public Map<String, Object> suggestArticleMeta(AuthPrincipal principal, String title, String content) {
        if (title == null || title.isBlank() || content == null || content.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先填写标题和正文，再获取 AI 建议");
        }
        AiService.ResolvedPrompt prompt = resolvePrompt("suggest");
        String rendered = render(prompt.content(), Map.of(
                "title", title.trim(),
                "content", content.length() > 3000 ? content.substring(0, 3000) : content));
        CallOutcome outcome = callModel(principal, "suggest", null, rendered, true);
        JsonNode node = parseJsonOutput(outcome.text());
        List<String> labels = new ArrayList<>();
        node.path("labels").forEach(l -> labels.add(l.asText()));
        return Map.of(
                "summary", node.path("summary").asText(""),
                "labels", labels,
                "modelName", outcome.model().getName(),
                "promptName", prompt.name());
    }

    /** 举报处置建议：{verdict, reason, action, note}。 */
    public Map<String, Object> reviewReport(AuthPrincipal principal, Map<String, String> input) {
        AiService.ResolvedPrompt prompt = resolvePrompt("report-review");
        String rendered = render(prompt.content(), input);
        CallOutcome outcome = callModel(principal, "report-review", null, rendered, false);
        JsonNode node = parseJsonOutput(outcome.text());
        return Map.of(
                "verdict", node.path("verdict").asText(""),
                "reason", node.path("reason").asText("other"),
                "action", node.path("action").asText(""),
                "note", node.path("note").asText(""),
                "modelName", outcome.model().getName());
    }

    private static final List<String> WRITING_ACTIONS = List.of("polish", "shorten", "expand", "continue");

    private static String writingActionLabel(String action) {
        return switch (action) {
            case "polish" -> "润色";
            case "shorten" -> "精简";
            case "expand" -> "扩写";
            case "continue" -> "续写";
            default -> action;
        };
    }

    /** 写作辅助：润色/精简/扩写/续写，返回纯文本候选（作者确认后才写编辑器）。 */
    public Map<String, Object> rewriteArticleText(AuthPrincipal principal, String action, String title, String text) {
        if (action == null || !WRITING_ACTIONS.contains(action)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的 AI 写作动作");
        }
        if (text == null || text.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先选择或填写正文，再使用 AI 写作辅助");
        }
        String actionLabel = writingActionLabel(action);
        String requirement = "continue".equals(action)
                ? "1. 在原文后自然续写 100-200 字，保持原有语境、人设和事实；"
                : "1. 对原文进行" + actionLabel + "，不添加原文没有的事实；";
        String plainTitle = title == null ? "" : title.trim();
        AiService.ResolvedPrompt prompt = resolvePrompt("writing");
        String rendered = render(prompt.content(), Map.of(
                "action", action,
                "actionLabel", actionLabel,
                "requirement", requirement,
                "title", plainTitle.length() > 150 ? plainTitle.substring(0, 150) : plainTitle,
                "titleLine", plainTitle.isEmpty() ? "" : "文章标题：" + plainTitle,
                "text", text.length() > 6000 ? text.substring(0, 6000) : text));
        CallOutcome outcome = callModel(principal, "writing", null, rendered, false);
        return Map.of(
                "text", outcome.text(),
                "modelName", outcome.model().getName());
    }

    /** 图库策展建议：{titles[], summary, labels[]}。 */
    public Map<String, Object> suggestGalleryCuration(AuthPrincipal principal, String title,
                                                      String introduce, String theme, int photoCount) {
        if ((title == null || title.isBlank()) && (introduce == null || introduce.isBlank())
                && (theme == null || theme.isBlank())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先填写标题、简介或补充主题，再获取 AI 策展建议");
        }
        AiService.ResolvedPrompt prompt = resolvePrompt("gallery");
        String rendered = render(prompt.content(), Map.of(
                "title", title == null ? "" : title.trim(),
                "introduce", introduce == null ? "" : introduce.trim(),
                "theme", theme == null ? "" : theme.trim(),
                "photoCount", String.valueOf(Math.max(0, photoCount))));
        CallOutcome outcome = callModel(principal, "gallery", null, rendered, true);
        JsonNode node = parseJsonOutput(outcome.text());
        List<String> titles = new ArrayList<>();
        node.path("titles").forEach(t -> titles.add(t.asText()));
        List<String> labels = new ArrayList<>();
        node.path("labels").forEach(l -> labels.add(l.asText()));
        return Map.of(
                "titles", titles,
                "summary", node.path("summary").asText(""),
                "labels", labels,
                "modelName", outcome.model().getName());
    }

    /** 运营周报：真实统计入参 → 纯文本周报。 */
    public String operationsReport(AuthPrincipal principal, Map<String, Object> stats) {
        AiService.ResolvedPrompt prompt = resolvePrompt("operations");
        Map<String, String> vars = new LinkedHashMap<>();
        stats.forEach((k, v) -> vars.put(k, String.valueOf(v)));
        String rendered = render(prompt.content(), vars);
        return callModel(principal, "operations", null, rendered, false).text();
    }

    /** 提示词试跑（AI 管理页验证模板）：任意提示词 + 变量。 */
    public Map<String, Object> testRun(AuthPrincipal principal, String prompt, Map<String, String> vars) {
        if (prompt == null || prompt.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "提示词不能为空");
        }
        String rendered = render(prompt, vars == null ? Map.of() : vars);
        CallOutcome outcome = callModel(principal, "test-run", null, rendered, false);
        return Map.of(
                "output", outcome.text(),
                "modelName", outcome.model().getName(),
                "durationMs", outcome.durationMs());
    }

    /** 私信拟回复：2 条候选话术（热情/克制），作者点选后才写输入框。 */
    public List<String> suggestMessageReplies(AuthPrincipal principal, String peerName,
                                              String history, String tone) {
        AiService.ResolvedPrompt prompt = resolvePrompt("message");
        String toneLine = tone == null || tone.isBlank() ? "" : "作者期望的语气：" + tone;
        String rendered = render(prompt.content(), Map.of(
                "peerName", peerName == null || peerName.isBlank() ? "对方" : peerName,
                "history", history == null ? "" : history,
                "tone", tone == null ? "" : tone,
                "toneLine", toneLine));
        CallOutcome outcome = callModel(principal, "message", null, rendered, true);
        JsonNode node = parseJsonOutput(outcome.text());
        List<String> replies = new ArrayList<>();
        node.path("replies").forEach(r -> {
            String reply = r.asText().trim();
            if (!reply.isEmpty() && replies.size() < 2) {
                replies.add(reply);
            }
        });
        if (replies.isEmpty()) {
            throw new BizException(ErrorCode.INTERNAL, "AI 未能生成可用回复，请重试");
        }
        return replies;
    }
}
