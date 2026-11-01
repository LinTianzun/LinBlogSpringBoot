package com.linblog.module.diary.controller;

import com.linblog.common.ApiResult;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.diary.dto.DiaryCreateRequest;
import com.linblog.module.diary.dto.DiaryUpdateRequest;
import com.linblog.module.diary.dto.DiaryView;
import com.linblog.module.diary.service.DiaryService;
import com.linblog.module.permission.PermissionKeys;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端日记接口（§5.4）：列表/详情 scope=viewAll/viewOwn；
 * 创建 diary.create；管理（update/delete）manageAll 或 manageOwn+归属。
 */
@RestController
@RequestMapping("/api/v1/admin/diaries")
public class DiaryAdminController {

    private final DiaryService diaryService;

    public DiaryAdminController(DiaryService diaryService) {
        this.diaryService = diaryService;
    }

    @GetMapping
    public ApiResult<PageResult<DiaryView>> page(@Valid PageQuery query,
                                                 @RequestParam(required = false) String state) {
        AuthPrincipal principal = requirePrincipal();
        boolean viewAll = principal.has(PermissionKeys.DIARY_VIEW_ALL);
        if (!viewAll && !principal.has(PermissionKeys.DIARY_MANAGE_OWN)
                && !principal.has(PermissionKeys.DIARY_CREATE)) {
            throw new BizException(ErrorCode.NOT_FOUND, "日记不存在");
        }
        return ApiResult.ok(diaryService.page(query, state, viewAll, principal.uid()));
    }

    @PostMapping
    public ApiResult<DiaryView> create(@Valid @RequestBody DiaryCreateRequest body) {
        return ApiResult.ok(diaryService.create(body));
    }

    @GetMapping("/{id}")
    public ApiResult<DiaryView> get(@PathVariable long id) {
        AuthPrincipal principal = requirePrincipal();
        boolean viewAll = principal.has(PermissionKeys.DIARY_VIEW_ALL);
        if (!viewAll && !principal.has(PermissionKeys.DIARY_MANAGE_OWN)) {
            throw new BizException(ErrorCode.NOT_FOUND, "日记不存在");
        }
        return ApiResult.ok(diaryService.get(id, viewAll, principal.uid()));
    }

    @PatchMapping("/{id}")
    public ApiResult<DiaryView> update(@PathVariable long id, @Valid @RequestBody DiaryUpdateRequest body) {
        return ApiResult.ok(diaryService.update(id, body));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        diaryService.delete(id);
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
