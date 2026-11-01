package com.linblog.module.audit.controller;

import com.linblog.common.ApiResult;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.audit.dto.SystemLogView;
import com.linblog.module.audit.service.SystemLogQueryService;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统日志接口（§5.8 GET /admin/system-logs?module&action&page，systemLog.view）。
 */
@RestController
@RequestMapping("/api/v1/admin/system-logs")
public class SystemLogAdminController {

    private final SystemLogQueryService systemLogQueryService;

    public SystemLogAdminController(SystemLogQueryService systemLogQueryService) {
        this.systemLogQueryService = systemLogQueryService;
    }

    @GetMapping
    public ApiResult<PageResult<SystemLogView>> page(@Valid PageQuery query,
                                                     @RequestParam(required = false) String module,
                                                     @RequestParam(required = false) String action) {
        AuthPrincipal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未登录");
        }
        return ApiResult.ok(systemLogQueryService.page(query, module, action, principal));
    }
}
