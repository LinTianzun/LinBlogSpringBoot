package com.linblog.module.permission.controller;

import com.linblog.common.ApiResult;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.permission.PermissionKeys;
import com.linblog.module.permission.service.PermissionService;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CurrentUser;
import com.linblog.security.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 权限接口（§5.2）：catalog=permission.view；me=登录；matrix=仅超管。
 */
@RestController
@RequestMapping("/api/v1/permissions")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /** 权限分组目录 + 受保护标记。 */
    @GetMapping("/catalog")
    public ApiResult<Map<String, Object>> catalog() {
        AuthPrincipal principal = CurrentUser.get();
        permissionService.require(principal, PermissionKeys.PERMISSION_VIEW);
        List<PermissionKeys.Group> groups = PermissionKeys.GROUPS.entrySet().stream()
                .map(e -> new PermissionKeys.Group(e.getKey(), e.getValue().stream()
                        .map(key -> new PermissionKeys.Item(key, PermissionKeys.labelOf(key),
                                PermissionKeys.PROTECTED_KEYS.contains(key)))
                        .toList()))
                .toList();
        return ApiResult.ok(Map.of(
                "groups", groups,
                "protectedKeys", PermissionKeys.PROTECTED_KEYS,
                "totalKeys", PermissionKeys.ALL_KEYS.size()));
    }

    /** 当前登录用户的角色与可用键（前端登录后首拉）。 */
    @GetMapping("/me")
    public ApiResult<Map<String, Object>> me() {
        AuthPrincipal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        return ApiResult.ok(Map.of(
                "role", principal.role(),
                "permissions", principal.permissions(),
                "isSuperAdmin", principal.superAdmin()));
    }

    public record MatrixRequest(Map<String, List<String>> matrix) {
    }

    /** 保存权限矩阵（全量覆盖 admin/editor/viewer 三行；super_admin 行拒绝）。 */
    @PutMapping("/matrix")
    public ApiResult<Map<String, Integer>> saveMatrix(@RequestBody MatrixRequest body, HttpServletRequest request) {
        AuthPrincipal principal = CurrentUser.get();
        return ApiResult.ok(permissionService.saveMatrix(principal, body.matrix(), IpUtil.clientIp(request)));
    }
}
