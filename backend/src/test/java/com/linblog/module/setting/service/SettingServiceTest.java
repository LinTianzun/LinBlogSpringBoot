package com.linblog.module.setting.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.setting.entity.SiteSetting;
import com.linblog.module.setting.mapper.SiteSettingMapper;
import com.linblog.security.AuthPrincipal;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 系统设置服务（§5.2/§6.1）：白名单 6 键、限长、trim、UPSERT 幂等、并发兜底、审计一次。
 */
class SettingServiceTest {

    private static final AuthPrincipal OPERATOR =
            new AuthPrincipal(1L, "root", "super_admin", true, Set.of());

    private SiteSettingMapper siteSettingMapper;
    private SystemLogService systemLogService;
    private SettingService service;

    @BeforeAll
    static void initEntityMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SiteSetting.class);
    }

    @BeforeEach
    void setUp() {
        siteSettingMapper = mock(SiteSettingMapper.class);
        systemLogService = mock(SystemLogService.class);
        service = new SettingService(siteSettingMapper, systemLogService);
    }

    private static SiteSetting row(String key, String value) {
        SiteSetting setting = new SiteSetting();
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        setting.setUpdatedBy(9L);
        return setting;
    }

    @Test
    void allReturnsWhitelistOrderWithDefaults() {
        when(siteSettingMapper.selectList(any())).thenReturn(
                List.of(row("site_title", "Lin"), row("announcement", "Hi")));

        Map<String, String> all = service.all();

        assertEquals(List.of("site_title", "site_description", "site_keywords",
                "icp", "footer_text", "announcement"), List.copyOf(all.keySet()));
        assertEquals("Lin", all.get("site_title"));
        assertEquals("Hi", all.get("announcement"));
        assertEquals("", all.get("icp"));
    }

    @Test
    void updateRejectsNullOrBlankMap() {
        assertEquals("settings 不能为空",
                assertThrows(BizException.class, () -> service.update(OPERATOR, null, "ip")).getMessage());
        assertEquals("settings 不能为空",
                assertThrows(BizException.class, () -> service.update(OPERATOR, Map.of(), "ip")).getMessage());
    }

    @Test
    void updateRejectsUnknownKeyBeforeAnyWrite() {
        assertEquals("未知的设置项: nope",
                assertThrows(BizException.class, () -> service.update(OPERATOR, Map.of("nope", "x"), "ip"))
                        .getMessage());
        verify(siteSettingMapper, never()).insert(any(SiteSetting.class));
        verify(siteSettingMapper, never()).update(isNull(), any());
    }

    @Test
    void updateRejectsOverLimitValues() {
        assertEquals("设置项 site_title 超过长度上限 50",
                assertThrows(BizException.class,
                        () -> service.update(OPERATOR, Map.of("site_title", "t".repeat(51)), "ip")).getMessage());
        assertEquals("设置项 announcement 超过长度上限 500",
                assertThrows(BizException.class,
                        () -> service.update(OPERATOR, Map.of("announcement", "a".repeat(501)), "ip")).getMessage());
    }

    @Test
    void updateTrimsValuesInsertsMissingKeysAndAuditsOnce() {
        when(siteSettingMapper.selectById(anyString())).thenReturn(null);

        // 显式 put 保证插入顺序（Map.of 无序），服务按传入顺序逐键 UPSERT
        LinkedHashMap<String, String> payload = new LinkedHashMap<>();
        payload.put("site_title", " My Blog ");
        payload.put("footer_text", "  ");
        Set<String> keys = service.update(OPERATOR, payload, "ip");

        assertEquals(Set.of("site_title", "footer_text"), keys);
        ArgumentCaptor<SiteSetting> created = ArgumentCaptor.forClass(SiteSetting.class);
        verify(siteSettingMapper, times(2)).insert(created.capture());
        assertEquals("site_title", created.getAllValues().get(0).getSettingKey());
        assertEquals("My Blog", created.getAllValues().get(0).getSettingValue());
        assertEquals(1L, created.getAllValues().get(0).getUpdatedBy());
        assertEquals("footer_text", created.getAllValues().get(1).getSettingKey());
        assertEquals("", created.getAllValues().get(1).getSettingValue());
        verify(systemLogService).record(1L, "setting", "update", "setting", null,
                "site_title,footer_text", "ip");
    }

    @Test
    void updateTakesUpdatePathWhenRowExists() {
        when(siteSettingMapper.selectById("site_title")).thenReturn(row("site_title", "Old"));

        service.update(OPERATOR, new LinkedHashMap<>(Map.of("site_title", "New")), "ip");

        verify(siteSettingMapper, never()).insert(any(SiteSetting.class));
        verify(siteSettingMapper).update(isNull(), any());
    }

    @Test
    void updateDuplicateKeyRaceFallsBackToUpdate() {
        when(siteSettingMapper.selectById("site_title")).thenReturn(null, row("site_title", "Other"));
        when(siteSettingMapper.insert(any(SiteSetting.class)))
                .thenThrow(new DuplicateKeyException("pk_site_settings"));

        service.update(OPERATOR, new LinkedHashMap<>(Map.of("site_title", "New")), "ip");

        verify(siteSettingMapper).update(isNull(), any());
        verify(systemLogService).record(anyLong(), anyString(), anyString(), anyString(), isNull(),
                anyString(), anyString());
    }

    @Test
    void updateDuplicateKeyRaceStillMissingFails() {
        when(siteSettingMapper.selectById(anyString())).thenReturn(null);
        when(siteSettingMapper.insert(any(SiteSetting.class)))
                .thenThrow(new DuplicateKeyException("pk_site_settings"));

        BizException exception = assertThrows(BizException.class,
                () -> service.update(OPERATOR, new LinkedHashMap<>(Map.of("site_title", "New")), "ip"));
        assertEquals(ErrorCode.INTERNAL, exception.getErrorCode());
    }
}
