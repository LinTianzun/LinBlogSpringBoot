package com.linblog.module.setting.controller;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.GlobalExceptionHandler;
import com.linblog.module.permission.PermissionKeys;
import com.linblog.module.permission.service.PermissionService;
import com.linblog.module.setting.service.SettingService;
import com.linblog.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 系统设置端点（§5.2）：GET 全量 / PUT 全量校验，system.setting 门禁，匿名 40100。
 */
class SettingControllerTest {

    private static final AuthPrincipal ROOT =
            new AuthPrincipal(1L, "root", "super_admin", true, Set.of("system.setting"));

    private SettingService settingService;
    private PermissionService permissionService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        settingService = mock(SettingService.class);
        permissionService = mock(PermissionService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SettingController(settingService, permissionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(AuthPrincipal principal) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, null));
    }

    @Test
    void allRejectsAnonymous() throws Exception {
        mvc.perform(request(HttpMethod.GET, "/api/v1/admin/settings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.code()));
    }

    @Test
    void allDelegatesWithSystemSettingPermission() throws Exception {
        loginAs(ROOT);
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("site_title", "Lin");
        settings.put("icp", "");
        when(settingService.all()).thenReturn(settings);

        mvc.perform(request(HttpMethod.GET, "/api/v1/admin/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.site_title").value("Lin"))
                .andExpect(jsonPath("$.data.icp").value(""));
        verify(permissionService).require(ROOT, PermissionKeys.SYSTEM_SETTING);
    }

    @Test
    void allRejectsWithoutPermission() throws Exception {
        loginAs(ROOT);
        doThrow(new BizException(ErrorCode.FORBIDDEN))
                .when(permissionService).require(ROOT, PermissionKeys.SYSTEM_SETTING);

        mvc.perform(request(HttpMethod.GET, "/api/v1/admin/settings"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.code()));
    }

    @Test
    void updateDelegatesWithClientIp() throws Exception {
        loginAs(ROOT);

        mvc.perform(request(HttpMethod.PUT, "/api/v1/admin/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":{\"site_title\":\"A\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> values = ArgumentCaptor.forClass(Map.class);
        verify(settingService).update(eq(ROOT), values.capture(), eq("127.0.0.1"));
        assertEquals(Map.of("site_title", "A"), values.getValue());
    }

    @Test
    void updatePassesNullSettingsThroughForServiceValidation() throws Exception {
        loginAs(ROOT);

        mvc.perform(request(HttpMethod.PUT, "/api/v1/admin/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(settingService).update(eq(ROOT), isNull(), eq("127.0.0.1"));
    }

    @Test
    void updateRejectsAnonymous() throws Exception {
        mvc.perform(request(HttpMethod.PUT, "/api/v1/admin/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":{\"site_title\":\"A\"}}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.code()));
    }
}
