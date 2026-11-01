package com.linblog.module.guestbook.controller;

import com.linblog.common.ApiResult;
import com.linblog.module.guestbook.dto.GuestbookCreateRequest;
import com.linblog.module.guestbook.dto.GuestbookCreateResult;
import com.linblog.module.guestbook.dto.GuestbookView;
import com.linblog.module.guestbook.service.GuestbookService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 留言墙（§5.6）：GET 公开（visible 一级 + 两级回复）；POST 登录；
 * DELETE 作者或 message.wallModerate（服务层校验，逻辑删）。
 */
@RestController
public class GuestbookController {

    private final GuestbookService guestbookService;

    public GuestbookController(GuestbookService guestbookService) {
        this.guestbookService = guestbookService;
    }

    @GetMapping("/api/v1/guestbook")
    public ApiResult<List<GuestbookView>> list() {
        return ApiResult.ok(guestbookService.list());
    }

    @PostMapping("/api/v1/guestbook")
    public ApiResult<GuestbookCreateResult> create(@Valid @RequestBody GuestbookCreateRequest body) {
        return ApiResult.ok(guestbookService.create(body));
    }

    @DeleteMapping("/api/v1/guestbook/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        guestbookService.delete(id);
        return ApiResult.ok(null);
    }
}
