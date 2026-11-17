package com.linblog.module.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linblog.common.ApiResult;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.ai.entity.AiModel;
import com.linblog.module.ai.entity.AiPromptBinding;
import com.linblog.module.ai.entity.AiPromptTemplate;
import com.linblog.module.ai.entity.AiSetting;
import com.linblog.module.ai.mapper.AiModelMapper;
import com.linblog.module.ai.mapper.AiPromptBindingMapper;
import com.linblog.module.ai.mapper.AiPromptTemplateMapper;
import com.linblog.module.ai.mapper.AiSettingMapper;
import com.linblog.module.ai.service.AiSecretBox;
import com.linblog.module.ai.service.AiService;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AI 管理（§AI，system.setting 权限）：模型接入 CRUD、策略、提示词模板与绑定、用量摘要。
 * 密钥只写不读：列表/详情一律掩码返回。
 */
@RestController
@RequestMapping("/api/v1/admin/ai")
public class AiAdminController {

    public record ModelRequest(@NotBlank @Size(max = 64) String name,
                               @NotBlank String baseUrl,
                               String apiKey,
                               @NotBlank @Size(max = 128) String chatModel,
                               @Size(max = 128) String imageModel,
                               @Size(max = 16) String imageSize,
                               Integer timeoutMs,
                               Boolean enabled,
                               String remark) {
    }

    public record SettingsRequest(Boolean quotaEnabled, Integer dailyLimit, Integer monthlyLimit,
                                  Boolean superAdminUnlimited, Integer chatModelId,
                                  Integer imageModelId, Boolean removeWatermark,
                                  String watermarkMode) {
    }

    public record TemplateRequest(@NotBlank @Size(max = 64) String templateKey,
                                  @Size(max = 64) String featureKey,
                                  @NotBlank @Size(max = 64) String name,
                                  @Size(max = 255) String description,
                                  @NotBlank String content,
                                  @Size(max = 500) String variables) {
    }

    private final AiModelMapper modelMapper;
    private final AiSettingMapper settingMapper;
    private final AiPromptTemplateMapper templateMapper;
    private final AiPromptBindingMapper bindingMapper;
    private final AiSecretBox secretBox;
    private final AiService aiService;
    private final SystemLogService systemLogService;

    public AiAdminController(AiModelMapper modelMapper, AiSettingMapper settingMapper,
                             AiPromptTemplateMapper templateMapper, AiPromptBindingMapper bindingMapper,
                             AiSecretBox secretBox, AiService aiService, SystemLogService systemLogService) {
        this.modelMapper = modelMapper;
        this.settingMapper = settingMapper;
        this.templateMapper = templateMapper;
        this.bindingMapper = bindingMapper;
        this.secretBox = secretBox;
        this.aiService = aiService;
        this.systemLogService = systemLogService;
    }

    private AuthPrincipal requireSettingPermission() {
        AuthPrincipal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        if (!principal.has(com.linblog.module.permission.PermissionKeys.SYSTEM_SETTING)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权限");
        }
        return principal;
    }

    private void requireSettingPermissionVoid() {
        requireSettingPermission();
    }

    /* ---------- 模型接入 ---------- */

    private Map<String, Object> modelView(AiModel m) {
        String masked;
        try {
            masked = secretBox.mask(secretBox.decrypt(m.getApiKeyCipher()));
        } catch (Exception e) {
            masked = "••••";
        }
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("id", m.getId());
        view.put("name", m.getName());
        view.put("baseUrl", m.getBaseUrl());
        view.put("apiKeyMasked", masked);
        view.put("chatModel", m.getChatModel());
        view.put("imageModel", m.getImageModel());
        view.put("imageSize", m.getImageSize());
        view.put("timeoutMs", m.getTimeoutMs());
        view.put("enabled", m.getEnabled());
        view.put("isDefault", m.getIsDefault());
        view.put("remark", m.getRemark() == null ? "" : m.getRemark());
        return view;
    }

    @GetMapping("/models")
    public ApiResult<List<Map<String, Object>>> models() {
        requireSettingPermissionVoid();
        return ApiResult.ok(modelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                        .orderByDesc(AiModel::getIsDefault).orderByAsc(AiModel::getId)).stream()
                .map(this::modelView)
                .toList());
    }

    @PostMapping("/models")
    public ApiResult<Map<String, Object>> createModel(@Valid @RequestBody ModelRequest body) {
        AuthPrincipal principal = requireSettingPermission();
        if (body.apiKey() == null || body.apiKey().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "API Key 不能为空");
        }
        AiModel model = new AiModel();
        applyModel(model, body);
        model.setApiKeyCipher(secretBox.encrypt(body.apiKey()));
        model.setIsDefault(modelMapper.selectCount(new LambdaQueryWrapper<AiModel>()) == 0);
        modelMapper.insert(model);
        systemLogService.record(principal.uid(), "ai", "create_model", "ai_model", model.getId().longValue(),
                model.getName(), null);
        return ApiResult.ok(modelView(model));
    }

    @PatchMapping("/models/{id}")
    public ApiResult<Map<String, Object>> updateModel(@PathVariable int id, @Valid @RequestBody ModelRequest body) {
        AuthPrincipal principal = requireSettingPermission();
        AiModel model = modelMapper.selectById(id);
        if (model == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "模型不存在");
        }
        applyModel(model, body);
        if (body.apiKey() != null && !body.apiKey().isBlank()) {
            model.setApiKeyCipher(secretBox.encrypt(body.apiKey()));
        }
        modelMapper.updateById(model);
        systemLogService.record(principal.uid(), "ai", "update_model", "ai_model", (long) id, model.getName(), null);
        return ApiResult.ok(modelView(model));
    }

    private void applyModel(AiModel model, ModelRequest body) {
        model.setName(body.name().trim());
        model.setProvider("openai");
        model.setBaseUrl(body.baseUrl().trim());
        model.setChatModel(body.chatModel().trim());
        model.setImageModel(body.imageModel() == null ? "" : body.imageModel().trim());
        model.setImageSize(body.imageSize() == null || body.imageSize().isBlank()
                ? "1344x768" : body.imageSize().trim());
        model.setTimeoutMs(body.timeoutMs() == null ? 20000 : Math.max(1000, body.timeoutMs()));
        model.setEnabled(body.enabled() == null || body.enabled());
        model.setRemark(body.remark());
    }

    @DeleteMapping("/models/{id}")
    public ApiResult<Void> deleteModel(@PathVariable int id) {
        AuthPrincipal principal = requireSettingPermission();
        AiModel model = modelMapper.selectById(id);
        if (model == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "模型不存在");
        }
        modelMapper.deleteById(id);
        systemLogService.record(principal.uid(), "ai", "delete_model", "ai_model", (long) id, model.getName(), null);
        return ApiResult.ok(null);
    }

    /** 设为默认模型（文本任务默认；原 is_default 唯一化）。 */
    @PutMapping("/models/{id}/active")
    public ApiResult<Void> activateModel(@PathVariable int id) {
        AuthPrincipal principal = requireSettingPermission();
        AiModel model = modelMapper.selectById(id);
        if (model == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "模型不存在");
        }
        for (AiModel other : modelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getIsDefault, true))) {
            other.setIsDefault(false);
            modelMapper.updateById(other);
        }
        model.setIsDefault(true);
        model.setEnabled(true);
        modelMapper.updateById(model);
        AiSetting setting = aiService.settings();
        setting.setChatModelId(id);
        settingMapper.updateById(setting);
        systemLogService.record(principal.uid(), "ai", "activate_model", "ai_model", (long) id, model.getName(), null);
        return ApiResult.ok(null);
    }

    /* ---------- 策略 ---------- */

    @GetMapping("/settings")
    public ApiResult<AiSetting> settings() {
        requireSettingPermissionVoid();
        return ApiResult.ok(aiService.settings());
    }

    @PutMapping("/settings")
    public ApiResult<AiSetting> updateSettings(@RequestBody SettingsRequest body) {
        AuthPrincipal principal = requireSettingPermission();
        AiSetting setting = aiService.settings();
        if (body.quotaEnabled() != null) setting.setQuotaEnabled(body.quotaEnabled());
        if (body.dailyLimit() != null) setting.setDailyLimit(Math.max(0, body.dailyLimit()));
        if (body.monthlyLimit() != null) setting.setMonthlyLimit(Math.max(0, body.monthlyLimit()));
        if (body.superAdminUnlimited() != null) setting.setSuperAdminUnlimited(body.superAdminUnlimited());
        if (body.chatModelId() != null) setting.setChatModelId(body.chatModelId());
        if (body.imageModelId() != null) setting.setImageModelId(body.imageModelId());
        if (body.removeWatermark() != null) setting.setRemoveWatermark(body.removeWatermark());
        if (body.watermarkMode() != null && !body.watermarkMode().isBlank()) {
            if (!List.of("crop", "crop-resize").contains(body.watermarkMode())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "watermarkMode 只支持 crop / crop-resize");
            }
            setting.setWatermarkMode(body.watermarkMode());
        }
        settingMapper.updateById(setting);
        systemLogService.record(principal.uid(), "ai", "update_settings", "ai_setting", 1L, null, null);
        return ApiResult.ok(setting);
    }

    /* ---------- 提示词模板 ---------- */

    @GetMapping("/prompt-templates")
    public ApiResult<List<AiPromptTemplate>> templates() {
        requireSettingPermissionVoid();
        return ApiResult.ok(templateMapper.selectList(new LambdaQueryWrapper<AiPromptTemplate>()
                .orderByAsc(AiPromptTemplate::getFeatureKey).orderByAsc(AiPromptTemplate::getId)));
    }

    @PostMapping("/prompt-templates")
    public ApiResult<AiPromptTemplate> createTemplate(@Valid @RequestBody TemplateRequest body) {
        AuthPrincipal principal = requireSettingPermission();
        if (templateMapper.selectCount(new LambdaQueryWrapper<AiPromptTemplate>()
                .eq(AiPromptTemplate::getTemplateKey, body.templateKey())) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "模板标识已存在");
        }
        AiPromptTemplate template = new AiPromptTemplate();
        applyTemplate(template, body);
        template.setIsDefault(false);
        template.setEnabled(true);
        templateMapper.insert(template);
        systemLogService.record(principal.uid(), "ai", "create_template", "ai_prompt_template",
                template.getId().longValue(), template.getName(), null);
        return ApiResult.ok(template);
    }

    @PatchMapping("/prompt-templates/{id}")
    public ApiResult<AiPromptTemplate> updateTemplate(@PathVariable int id, @Valid @RequestBody TemplateRequest body) {
        AuthPrincipal principal = requireSettingPermission();
        AiPromptTemplate template = templateMapper.selectById(id);
        if (template == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "模板不存在");
        }
        applyTemplate(template, body);
        templateMapper.updateById(template);
        systemLogService.record(principal.uid(), "ai", "update_template", "ai_prompt_template",
                (long) id, template.getName(), null);
        return ApiResult.ok(template);
    }

    private void applyTemplate(AiPromptTemplate template, TemplateRequest body) {
        template.setTemplateKey(body.templateKey().trim());
        template.setFeatureKey(body.featureKey() == null ? "" : body.featureKey().trim());
        template.setName(body.name().trim());
        template.setDescription(body.description());
        template.setContent(body.content());
        template.setVariables(body.variables());
    }

    @DeleteMapping("/prompt-templates/{id}")
    public ApiResult<Void> deleteTemplate(@PathVariable int id) {
        AuthPrincipal principal = requireSettingPermission();
        AiPromptTemplate template = templateMapper.selectById(id);
        if (template == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "模板不存在");
        }
        templateMapper.deleteById(id);
        bindingMapper.delete(new LambdaQueryWrapper<AiPromptBinding>()
                .eq(AiPromptBinding::getTemplateKey, template.getTemplateKey()));
        systemLogService.record(principal.uid(), "ai", "delete_template", "ai_prompt_template",
                (long) id, template.getName(), null);
        return ApiResult.ok(null);
    }

    /** 模板启用/停用（停用即回退内置）。 */
    @PatchMapping("/prompt-templates/{id}/enabled")
    public ApiResult<AiPromptTemplate> toggleTemplate(@PathVariable int id, @RequestBody Map<String, Boolean> body) {
        requireSettingPermissionVoid();
        AiPromptTemplate template = templateMapper.selectById(id);
        if (template == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "模板不存在");
        }
        template.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        templateMapper.updateById(template);
        return ApiResult.ok(template);
    }

    /** 功能 → 绑定模板（feature: suggest/report-review/operations）。 */
    @PutMapping("/bindings/{feature}")
    public ApiResult<Void> bindTemplate(@PathVariable String feature, @RequestBody Map<String, String> body) {
        requireSettingPermissionVoid();
        String templateKey = body.get("templateKey");
        if (templateKey == null || templateKey.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "templateKey 不能为空");
        }
        if (templateMapper.selectCount(new LambdaQueryWrapper<AiPromptTemplate>()
                .eq(AiPromptTemplate::getTemplateKey, templateKey)) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "模板不存在");
        }
        AiPromptBinding binding = bindingMapper.selectById(feature);
        if (binding == null) {
            binding = new AiPromptBinding();
            binding.setFeatureKey(feature);
        }
        binding.setTemplateKey(templateKey);
        bindingMapper.insertOrUpdate(binding);
        return ApiResult.ok(null);
    }

    /* ---------- 用量 ---------- */

    @GetMapping("/usage")
    public ApiResult<Map<String, Object>> usage() {
        requireSettingPermissionVoid();
        return ApiResult.ok(aiService.usageSummary());
    }
}
