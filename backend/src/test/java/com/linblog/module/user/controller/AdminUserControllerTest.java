package com.linblog.module.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.GlobalExceptionHandler;
import com.linblog.common.PageResult;
import com.linblog.module.permission.PermissionKeys;
import com.linblog.module.permission.service.PermissionService;
import com.linblog.module.user.service.AdminUserService;
import com.linblog.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 管理端用户 Web 层（standalone MockMvc）：权限判定、响应壳、@Valid 校验。 */
class AdminUserControllerTest {

    private AdminUserService adminUserService;
    private PermissionService permissionService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() throws Exception {
        adminUserService = Mockito.mock(AdminUserService.class);
        permissionService = Mockito.mock(PermissionService.class);
        AdminUserController controller = new AdminUserController(adminUserService, permissionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        principal = new AuthPrincipal(1L, "root", "super_admin", true,
                Set.of(PermissionKeys.USER_VIEW, PermissionKeys.USER_UPDATE, PermissionKeys.USER_DISABLE,
                        PermissionKeys.USER_RESET_PASSWORD, PermissionKeys.USER_ASSIGN_ADMIN));
        login();
    }

    private void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private AdminUserService.AdminUserView view(long id, String username, String role, int status) {
        return new AdminUserService.AdminUserView(id, username, username + "@linlog.com", username,
                role, status, LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    @Test
    void pageReturnsShellWithRecords() throws Exception {
        when(adminUserService.page(any(), any()))
                .thenReturn(PageResult.of(List.of(view(5L, "neo", "editor", 1)), 1));

        mockMvc.perform(get("/api/v1/admin/users").param("page", "1").param("pageSize", "10")
                        .param("keyword", "neo").param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records[0].username").value("neo"))
                .andExpect(jsonPath("$.data.records[0].role").value("editor"))
                .andExpect(jsonPath("$.data.total").value(1));

        verify(permissionService).require(principal, PermissionKeys.USER_VIEW);
    }

    @Test
    void createReturnsViewAndRequiresUserUpdate() throws Exception {
        when(adminUserService.create(eq("neo"), eq("neo@linlog.com"), eq("Password8"),
                isNull(), eq(principal), any())).thenReturn(view(6L, "neo", "viewer", 1));

        String body = objectMapper.writeValueAsString(
                new AdminUserController.CreateUserRequest("neo", "neo@linlog.com", "Password8", null));
        mockMvc.perform(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(6))
                .andExpect(jsonPath("$.data.status").value(1));

        verify(permissionService).require(principal, PermissionKeys.USER_UPDATE);
    }

    @Test
    void createValidationFailureReturns40001WithFieldErrors() throws Exception {
        String body = objectMapper.writeValueAsString(
                new AdminUserController.CreateUserRequest("x!", "not-an-email", "short", null));
        mockMvc.perform(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.data.fieldErrors.length()").value(3));
    }

    @Test
    void updateStatusMapsBodyAndRequiresUserDisable() throws Exception {
        when(adminUserService.updateStatus(eq(5L), eq(0), eq(principal), any()))
                .thenReturn(view(5L, "neo", "editor", 0));

        String body = objectMapper.writeValueAsString(new AdminUserController.StatusRequest(0));
        mockMvc.perform(patch("/api/v1/admin/users/5/status")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value(0));

        verify(permissionService).require(principal, PermissionKeys.USER_DISABLE);
    }

    @Test
    void resetPasswordReturnsNullData() throws Exception {
        String body = objectMapper.writeValueAsString(new AdminUserController.ResetPasswordRequest("Password8"));
        mockMvc.perform(patch("/api/v1/admin/users/5/password")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(adminUserService).resetPassword(eq(5L), eq("Password8"), eq(principal), any());
        verify(permissionService).require(principal, PermissionKeys.USER_RESET_PASSWORD);
    }

    @Test
    void updateRoleRequiresUserAssignAdmin() throws Exception {
        when(adminUserService.updateRole(eq(5L), eq("editor"), eq(principal), any()))
                .thenReturn(view(5L, "neo", "editor", 1));

        String body = objectMapper.writeValueAsString(new AdminUserController.RoleRequest("editor"));
        mockMvc.perform(patch("/api/v1/admin/users/5/role")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.role").value("editor"));

        verify(permissionService).require(principal, PermissionKeys.USER_ASSIGN_ADMIN);
    }

    @Test
    void missingPermissionMapsTo40300() throws Exception {
        doThrow(new BizException(ErrorCode.FORBIDDEN, "无权限"))
                .when(permissionService).require(any(), eq(PermissionKeys.USER_VIEW));

        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void serviceConflictMapsTo40900() throws Exception {
        when(adminUserService.page(any(), any()))
                .thenThrow(new BizException(ErrorCode.CONFLICT, "状态冲突"));

        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900))
                .andExpect(jsonPath("$.message").value("状态冲突"));
    }
}
