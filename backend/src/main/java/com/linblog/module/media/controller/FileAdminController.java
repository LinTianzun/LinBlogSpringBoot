package com.linblog.module.media.controller;

import com.linblog.common.ApiResult;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.media.dto.FileAdminView;
import com.linblog.module.media.dto.StorageStatus;
import com.linblog.module.media.service.FileAdminService;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CurrentUser;
import com.linblog.security.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台文件管理接口（§5.7）：列表 ?state&page、storage-status 汇总、
 * trash/restore（软删进备份区）、purge（删驱动对象+元数据，仅备份区）。
 */
@RestController
@RequestMapping("/api/v1/admin/files")
public class FileAdminController {

    private final FileAdminService fileAdminService;

    public FileAdminController(FileAdminService fileAdminService) {
        this.fileAdminService = fileAdminService;
    }

    @GetMapping
    public ApiResult<PageResult<FileAdminView>> page(@Valid PageQuery query,
                                                     @RequestParam(required = false) String state) {
        return ApiResult.ok(fileAdminService.page(query, state, requirePrincipal()));
    }

    @GetMapping("/storage-status")
    public ApiResult<StorageStatus> storageStatus() {
        return ApiResult.ok(fileAdminService.storageStatus(requirePrincipal()));
    }

    @PatchMapping("/{id}/trash")
    public ApiResult<Void> trash(@PathVariable long id, HttpServletRequest request) {
        fileAdminService.trash(id, requirePrincipal(), IpUtil.clientIp(request));
        return ApiResult.ok(null);
    }

    @PatchMapping("/{id}/restore")
    public ApiResult<Void> restore(@PathVariable long id, HttpServletRequest request) {
        fileAdminService.restore(id, requirePrincipal(), IpUtil.clientIp(request));
        return ApiResult.ok(null);
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> purge(@PathVariable long id, HttpServletRequest request) {
        fileAdminService.purge(id, requirePrincipal(), IpUtil.clientIp(request));
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
