package com.linblog.module.setting.controller;

import com.linblog.common.ApiResult;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.permission.PermissionKeys;
import com.linblog.module.permission.service.PermissionService;
import com.linblog.module.setting.service.SettingService;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CurrentUser;
import com.linblog.security.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 系统设置端点（§5.2/§6.1，M4）：/api/v1/admin/settings，system.setting。 */
@RestController
@RequestMapping("/api/v1/admin/settings")
public class SettingController {

    public record SettingsUpdateRequest(Map<String, String> settings) {}

    private final SettingService settingService;
    private final PermissionService permissionService;

    public SettingController(SettingService settingService, PermissionService permissionService) {
        this.settingService = settingService;
        this.permissionService = permissionService;
    }

    @GetMapping
    public ApiResult<Map<String, String>> all() {
        permissionService.require(requirePrincipal(), PermissionKeys.SYSTEM_SETTING);
        return ApiResult.ok(settingService.all());
    }

    @PutMapping
    public ApiResult<Void> update(@RequestBody SettingsUpdateRequest request, HttpServletRequest httpRequest) {
        AuthPrincipal principal = requirePrincipal();
        permissionService.require(principal, PermissionKeys.SYSTEM_SETTING);
        settingService.update(principal, request == null ? null : request.settings(), IpUtil.clientIp(httpRequest));
        return ApiResult.ok(null);
    }

    private AuthPrincipal requirePrincipal() {
        AuthPrincipal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未登录");
        }
        return principal;
    }
}
