package com.linblog.module.user.controller;

import com.linblog.common.ApiResult;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.permission.PermissionKeys;
import com.linblog.module.permission.service.PermissionService;
import com.linblog.module.user.service.AdminUserService;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CurrentUser;
import com.linblog.security.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端用户接口（§5.3）：view 查、update 建/改、disable 状态、resetPassword 密码、
 * assignAdmin 角色（仅超管）。
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = "^[a-zA-Z0-9_]{3,20}$", message = "用户名须为 3-20 位字母、数字或下划线") String username,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @Size(max = 50) String nickname) {
    }

    public record UpdateUserRequest(@Size(max = 50) String nickname, @Email String email) {
    }

    public record StatusRequest(@NotNull Integer status) {
    }

    public record ResetPasswordRequest(@NotBlank @Size(min = 8, max = 72) String password) {
    }

    public record RoleRequest(@NotBlank String role) {
    }

    private final AdminUserService adminUserService;
    private final PermissionService permissionService;

    public AdminUserController(AdminUserService adminUserService, PermissionService permissionService) {
        this.adminUserService = adminUserService;
        this.permissionService = permissionService;
    }

    @GetMapping
    public ApiResult<PageResult<AdminUserService.AdminUserView>> page(
            @Valid PageQuery query, @RequestParam(required = false) Integer status) {
        AuthPrincipal principal = CurrentUser.get();
        permissionService.require(principal, PermissionKeys.USER_VIEW);
        return ApiResult.ok(adminUserService.page(query, status));
    }

    @PostMapping
    public ApiResult<AdminUserService.AdminUserView> create(@Valid @RequestBody CreateUserRequest body,
                                                            HttpServletRequest request) {
        AuthPrincipal principal = CurrentUser.get();
        permissionService.require(principal, PermissionKeys.USER_UPDATE);
        return ApiResult.ok(adminUserService.create(body.username(), body.email(), body.password(),
                body.nickname(), principal, IpUtil.clientIp(request)));
    }

    @PatchMapping("/{id}")
    public ApiResult<AdminUserService.AdminUserView> update(@PathVariable long id,
                                                            @Valid @RequestBody UpdateUserRequest body,
                                                            HttpServletRequest request) {
        AuthPrincipal principal = CurrentUser.get();
        permissionService.require(principal, PermissionKeys.USER_UPDATE);
        return ApiResult.ok(adminUserService.update(id, body.nickname(), body.email(), principal,
                IpUtil.clientIp(request)));
    }

    @PatchMapping("/{id}/status")
    public ApiResult<AdminUserService.AdminUserView> updateStatus(@PathVariable long id,
                                                                  @Valid @RequestBody StatusRequest body,
                                                                  HttpServletRequest request) {
        AuthPrincipal principal = CurrentUser.get();
        permissionService.require(principal, PermissionKeys.USER_DISABLE);
        return ApiResult.ok(adminUserService.updateStatus(id, body.status(), principal, IpUtil.clientIp(request)));
    }

    @PatchMapping("/{id}/password")
    public ApiResult<Void> resetPassword(@PathVariable long id, @Valid @RequestBody ResetPasswordRequest body,
                                         HttpServletRequest request) {
        AuthPrincipal principal = CurrentUser.get();
        permissionService.require(principal, PermissionKeys.USER_RESET_PASSWORD);
        adminUserService.resetPassword(id, body.password(), principal, IpUtil.clientIp(request));
        return ApiResult.ok(null);
    }

    @PatchMapping("/{id}/role")
    public ApiResult<AdminUserService.AdminUserView> updateRole(@PathVariable long id,
                                                                @Valid @RequestBody RoleRequest body,
                                                                HttpServletRequest request) {
        AuthPrincipal principal = CurrentUser.get();
        permissionService.require(principal, PermissionKeys.USER_ASSIGN_ADMIN);
        return ApiResult.ok(adminUserService.updateRole(id, body.role(), principal, IpUtil.clientIp(request)));
    }
}
