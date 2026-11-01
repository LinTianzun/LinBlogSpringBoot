package com.linblog.module.content.controller;

import com.linblog.common.ApiResult;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.content.dto.ContentCreateRequest;
import com.linblog.module.content.dto.ContentStateRequest;
import com.linblog.module.content.dto.ContentUpdateRequest;
import com.linblog.module.content.dto.ContentView;
import com.linblog.module.content.service.ContentService;
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
 * 管理端内容接口（§5.3）：列表 scope=viewAll/viewOwn；回收站 deleted→draft=还原，
 * DELETE 入回收站，purge 彻底删除（仅 manageAll）。
 */
@RestController
@RequestMapping("/api/v1/admin/contents")
public class ContentAdminController {

    private final ContentService contentService;

    public ContentAdminController(ContentService contentService) {
        this.contentService = contentService;
    }

    @GetMapping
    public ApiResult<PageResult<ContentView>> page(
            @Valid PageQuery query,
            @RequestParam(required = false) String contentType,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Long labelId) {
        AuthPrincipal principal = requirePrincipal();
        boolean viewAll = ContentService.canViewAll(principal, contentType);
        if (!viewAll && !ContentService.canViewOwn(principal, contentType)) {
            // §7.3 防探测：读取类权限不足 → 40400
            throw new BizException(ErrorCode.NOT_FOUND, "内容不存在");
        }
        return ApiResult.ok(contentService.adminPage(query, contentType, state, labelId,
                viewAll, principal.uid()));
    }

    @PostMapping
    public ApiResult<ContentView> create(@Valid @RequestBody ContentCreateRequest body) {
        return ApiResult.ok(contentService.create(body));
    }

    @GetMapping("/{id}")
    public ApiResult<ContentView> get(@PathVariable long id) {
        AuthPrincipal principal = requirePrincipal();
        return ApiResult.ok(contentService.adminGet(id, principal));
    }

    @PatchMapping("/{id}")
    public ApiResult<ContentView> update(@PathVariable long id,
                                         @Valid @RequestBody ContentUpdateRequest body) {
        return ApiResult.ok(contentService.update(id, body));
    }

    @PatchMapping("/{id}/state")
    public ApiResult<ContentView> changeState(@PathVariable long id,
                                              @Valid @RequestBody ContentStateRequest body) {
        return ApiResult.ok(contentService.changeState(id, body));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> trash(@PathVariable long id) {
        contentService.delete(id);
        return ApiResult.ok(null);
    }

    @DeleteMapping("/{id}/purge")
    public ApiResult<Void> purge(@PathVariable long id) {
        contentService.purge(id);
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
