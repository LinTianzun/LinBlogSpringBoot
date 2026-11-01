package com.linblog.module.content.controller;

import com.linblog.common.ApiResult;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.content.dto.LabelRequest;
import com.linblog.module.content.entity.Label;
import com.linblog.module.content.service.LabelService;
import com.linblog.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标签（§5.3）：GET /api/v1/labels 公开；写操作 label.manage。
 */
@RestController
public class LabelController {

    private final LabelService labelService;

    public LabelController(LabelService labelService) {
        this.labelService = labelService;
    }

    @GetMapping("/api/v1/labels")
    public ApiResult<List<Label>> list() {
        return ApiResult.ok(labelService.listAll());
    }

    @PostMapping("/api/v1/admin/labels")
    public ApiResult<Label> create(@Valid @RequestBody LabelRequest body) {
        requirePrincipal();
        return ApiResult.ok(labelService.create(body));
    }

    @PatchMapping("/api/v1/admin/labels/{id}")
    public ApiResult<Label> update(@PathVariable long id, @Valid @RequestBody LabelRequest body) {
        requirePrincipal();
        return ApiResult.ok(labelService.update(id, body));
    }

    @DeleteMapping("/api/v1/admin/labels/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        requirePrincipal();
        labelService.delete(id);
        return ApiResult.ok(null);
    }

    private void requirePrincipal() {
        if (CurrentUser.get() == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未登录");
        }
    }
}
