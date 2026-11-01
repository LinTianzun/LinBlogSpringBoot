package com.linblog.module.permission.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linblog.common.GlobalExceptionHandler;
import com.linblog.module.permission.PermissionKeys;
import com.linblog.module.permission.service.PermissionService;
import com.linblog.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 权限 Web 层（standalone MockMvc）：catalog 目录结构、me 回显、matrix 透传。 */
class PermissionControllerTest {

    private PermissionService permissionService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        permissionService = Mockito.mock(PermissionService.class);
        PermissionController controller = new PermissionController(permissionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        principal = new AuthPrincipal(1L, "root", "super_admin", true,
                Set.copyOf(PermissionKeys.ALL_KEYS));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void catalogReturnsGroupsProtectedKeysAndTotalCount() throws Exception {
        mockMvc.perform(get("/api/v1/permissions/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.groups").isArray())
                .andExpect(jsonPath("$.data.totalKeys").value(PermissionKeys.ALL_KEYS.size()));

        verify(permissionService).require(principal, PermissionKeys.PERMISSION_VIEW);
    }

    @Test
    void meEchoesRolePermissionsAndSuperAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/permissions/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.role").value("super_admin"))
                .andExpect(jsonPath("$.data.isSuperAdmin").value(true));
    }

    @Test
    void meWithoutPrincipalMapsTo40100() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(get("/api/v1/permissions/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void matrixDelegatesToServiceWithPrincipalAndMatrix() throws Exception {
        Map<String, List<String>> matrix = Map.of("editor", List.of("article.viewAll", "diary.view"));
        when(permissionService.saveMatrix(eq(principal), any(), any()))
                .thenReturn(Map.of("editor", 2));

        String body = objectMapper.writeValueAsString(new PermissionController.MatrixRequest(matrix));
        mockMvc.perform(put("/api/v1/permissions/matrix")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.editor").value(2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, List<String>>> captor = ArgumentCaptor.forClass(Map.class);
        verify(permissionService).saveMatrix(eq(principal), captor.capture(), any());
        assertEquals(matrix, captor.getValue());
    }
}
