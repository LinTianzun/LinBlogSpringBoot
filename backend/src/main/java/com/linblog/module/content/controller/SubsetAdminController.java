package com.linblog.module.content.controller;

import com.linblog.common.ApiResult;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.content.dto.SubsetCreateRequest;
import com.linblog.module.content.dto.SubsetItemsRequest;
import com.linblog.module.content.dto.SubsetUpdateRequest;
import com.linblog.module.content.entity.Subset;
import com.linblog.module.content.entity.SubsetItem;
import com.linblog.module.content.service.ContentService;
import com.linblog.module.content.service.SubsetService;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 合集管理：§5 未定义端点，自设计（汇报偏差）。
 * 列表 viewAll 全量 / viewOwn 仅 own；创建 article.create|gallery.create；
 * 管理（update/items/delete）manageAll 或 owner。
 */
@RestController
@RequestMapping("/api/v1/admin/subsets")
public class SubsetAdminController {

    private final SubsetService subsetService;

    public SubsetAdminController(SubsetService subsetService) {
        this.subsetService = subsetService;
    }

    @GetMapping
    public ApiResult<PageResult<Subset>> page(@Valid PageQuery query) {
        AuthPrincipal principal = requirePrincipal();
        boolean viewAll = principal.hasAny(
                com.linblog.module.permission.PermissionKeys.ARTICLE_VIEW_ALL,
                com.linblog.module.permission.PermissionKeys.GALLERY_VIEW_ALL);
        boolean viewOwn = principal.hasAny(
                com.linblog.module.permission.PermissionKeys.ARTICLE_VIEW_OWN,
                com.linblog.module.permission.PermissionKeys.GALLERY_VIEW_OWN);
        if (!viewAll && !viewOwn) {
            throw new BizException(ErrorCode.NOT_FOUND, "合集不存在");
        }
        return ApiResult.ok(subsetService.page(query, viewAll, principal.uid()));
    }

    @PostMapping
    public ApiResult<Subset> create(@Valid @RequestBody SubsetCreateRequest body) {
        return ApiResult.ok(subsetService.create(body));
    }

    @GetMapping("/{id}")
    public ApiResult<Subset> get(@PathVariable long id) {
        requirePrincipal();
        return ApiResult.ok(subsetService.get(id));
    }

    @PatchMapping("/{id}")
    public ApiResult<Void> update(@PathVariable long id, @Valid @RequestBody SubsetUpdateRequest body) {
        subsetService.update(id, body);
        return ApiResult.ok(null);
    }

    @PutMapping("/{id}/items")
    public ApiResult<Void> replaceItems(@PathVariable long id, @Valid @RequestBody SubsetItemsRequest body) {
        subsetService.replaceItems(id, body);
        return ApiResult.ok(null);
    }

    @GetMapping("/{id}/items")
    public ApiResult<List<SubsetItem>> items(@PathVariable long id) {
        requirePrincipal();
        return ApiResult.ok(subsetService.itemsOf(id));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        subsetService.delete(id);
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
