package com.linblog.module.ai.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AI 编排：模型/提示词解析回退、配额、JSON 建议解析、用量落账。 */
class AiServiceTest {

    private AiModelMapper modelMapper;
    private AiSettingMapper settingMapper;
    private AiUsageLogMapper usageLogMapper;
    private AiPromptTemplateMapper templateMapper;
    private AiPromptBindingMapper bindingMapper;
    private AiClientService client;
    private AiService service;

    private final AuthPrincipal user = new AuthPrincipal(9L, "alice", "editor", false, Set.of());
    private final AuthPrincipal root = new AuthPrincipal(1L, "admin", "super_admin", true, Set.of());

    @BeforeEach
    void setUp() {
        modelMapper = mock(AiModelMapper.class);
        settingMapper = mock(AiSettingMapper.class);
        usageLogMapper = mock(AiUsageLogMapper.class);
        templateMapper = mock(AiPromptTemplateMapper.class);
        bindingMapper = mock(AiPromptBindingMapper.class);
        client = mock(AiClientService.class);
        service = new AiService(modelMapper, settingMapper, usageLogMapper, templateMapper,
                bindingMapper, client, new ObjectMapper());
    }

    private AiSetting settings(boolean quotaEnabled, int daily) {
        AiSetting setting = new AiSetting();
        setting.setId(1);
        setting.setQuotaEnabled(quotaEnabled);
        setting.setDailyLimit(daily);
        setting.setMonthlyLimit(100);
        setting.setSuperAdminUnlimited(true);
        when(settingMapper.selectById(1)).thenReturn(setting);
        return setting;
    }

    private AiModel enabledModel() {
        AiModel model = new AiModel();
        model.setId(3);
        model.setName("主力模型");
        model.setBaseUrl("https://api.test.local/v1");
        model.setApiKeyCipher("v1:a:b:c");
        model.setChatModel("gpt-test");
        model.setEnabled(true);
        model.setIsDefault(true);
        return model;
    }

    @Test
    void resolveModelPrefersSettingThenDefault() {
        settings(false, 0);
        when(modelMapper.selectById(7)).thenReturn(null);
        when(modelMapper.selectList(any())).thenReturn(List.of(enabledModel()));

        assertEquals("gpt-test", service.resolveModel().getChatModel());
    }

    @Test
    void resolveModelWithoutAnyThrows503() {
        settings(false, 0);
        BizException ex = assertThrows(BizException.class, () -> service.resolveModel());
        assertEquals(ErrorCode.INTERNAL, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("未配置"));
    }

    @Test
    void suggestParsesJsonAndLogsUsage() {
        settings(false, 0);
        when(modelMapper.selectList(any())).thenReturn(List.of(enabledModel()));
        when(client.chatCompletion(any(), any(), anyString(), eq(true)))
                .thenReturn(new AiClientService.ChatResult("{\"summary\":\"好的简介\",\"labels\":[\"a\",\"b\"]}", 10, 8));

        Map<String, Object> out = service.suggestArticleMeta(user, "标题", "正文正文");

        assertEquals("好的简介", out.get("summary"));
        assertEquals(List.of("a", "b"), out.get("labels"));
        ArgumentCaptor<AiUsageLog> captor = ArgumentCaptor.forClass(AiUsageLog.class);
        verify(usageLogMapper).insert(captor.capture());
        assertEquals("success", captor.getValue().getStatus());
        assertEquals("suggest", captor.getValue().getFeature());
        assertEquals(8, captor.getValue().getCompletionTokens());
    }

    @Test
    void suggestRejectsBlankInput() {
        BizException ex = assertThrows(BizException.class,
                () -> service.suggestArticleMeta(user, " ", ""));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(usageLogMapper, never()).insert(any(AiUsageLog.class));
    }

    @Test
    void quotaDeniedLogsAndBlocks() {
        settings(true, 0);
        when(usageLogMapper.selectCount(any())).thenReturn(5L); // 已达 5 次上限（daily_limit=5 时）
        AiSetting setting = settings(true, 5);

        BizException ex = assertThrows(BizException.class,
                () -> service.testRun(user, "讲个故事", Map.of()));
        assertEquals(ErrorCode.RATE_LIMITED, ex.getErrorCode());
        verify(usageLogMapper).insert(org.mockito.ArgumentMatchers.argThat(
                (AiUsageLog row) -> "denied".equals(row.getStatus())));
        verify(client, never()).chatCompletion(any(), any(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void superAdminBypassesQuota() {
        settings(true, 0);
        when(modelMapper.selectList(any())).thenReturn(List.of(enabledModel()));
        when(client.chatCompletion(any(), any(), anyString(), eq(false)))
                .thenReturn(new AiClientService.ChatResult("周报正文", 10, 20));

        String report = service.operationsReport(root, Map.of("days", 7, "visits", 5));

        assertEquals("周报正文", report);
        verify(usageLogMapper).insert(org.mockito.ArgumentMatchers.argThat(
                (AiUsageLog row) -> "success".equals(row.getStatus())));
    }

    @Test
    void promptFallsBackToBuiltinWhenBindingMissing() {
        settings(false, 0);
        when(bindingMapper.selectById("suggest")).thenReturn(null);

        AiService.ResolvedPrompt prompt = service.resolvePrompt("suggest");
        assertTrue(prompt.builtin());
        assertTrue(prompt.content().contains("{{title}}"));
    }

    @Test
    void disabledTemplateFallsBackToBuiltin() {
        settings(false, 0);
        AiPromptBinding binding = new AiPromptBinding();
        binding.setFeatureKey("suggest");
        binding.setTemplateKey("custom");
        when(bindingMapper.selectById("suggest")).thenReturn(binding);
        // 启用模板查不到（停用即查无）→ 回退内置
        when(templateMapper.selectOne(any())).thenReturn(null);

        assertTrue(service.resolvePrompt("suggest").builtin());
    }

    @Test
    void reviewReturnsParsedSuggestion() {
        settings(false, 0);
        when(modelMapper.selectList(any())).thenReturn(List.of(enabledModel()));
        when(client.chatCompletion(any(), any(), anyString(), eq(false)))
                .thenReturn(new AiClientService.ChatResult(
                        "{\"verdict\":\"成立\",\"reason\":\"spam\",\"action\":\"process\",\"note\":\"广告\"}", 10, 8));

        Map<String, Object> out = service.reviewReport(user, Map.of("commentContent", "广告内容"));
        assertEquals("成立", out.get("verdict"));
        assertEquals("process", out.get("action"));
    }

    @Test
    void rewriteValidatesActionAndReturnsText() {
        settings(false, 0);
        when(modelMapper.selectList(any())).thenReturn(List.of(enabledModel()));
        when(client.chatCompletion(any(), any(), anyString(), eq(false)))
                .thenReturn(new AiClientService.ChatResult("润色后的正文。", 10, 8));

        Map<String, Object> out = service.rewriteArticleText(user, "polish", "标题", "原文");
        assertEquals("润色后的正文。", out.get("text"));

        // 非法动作
        BizException ex = assertThrows(BizException.class,
                () -> service.rewriteArticleText(user, "translate", "标题", "原文"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        // 空正文
        ex = assertThrows(BizException.class,
                () -> service.rewriteArticleText(user, "polish", "标题", " "));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void curationParsesTitlesSummaryLabels() {
        settings(false, 0);
        when(modelMapper.selectList(any())).thenReturn(List.of(enabledModel()));
        when(client.chatCompletion(any(), any(), anyString(), eq(true)))
                .thenReturn(new AiClientService.ChatResult(
                        "{\"titles\":[\"A\",\"B\",\"C\"],\"summary\":\"策展简介\",\"labels\":[\"摄影\"]}", 10, 8));

        Map<String, Object> out = service.suggestGalleryCuration(user, "", "", "夜景", 3);
        assertEquals(List.of("A", "B", "C"), out.get("titles"));
        assertEquals("策展简介", out.get("summary"));

        // 全空入参 → 40000
        BizException ex = assertThrows(BizException.class,
                () -> service.suggestGalleryCuration(user, " ", "", " ", 0));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }
}
