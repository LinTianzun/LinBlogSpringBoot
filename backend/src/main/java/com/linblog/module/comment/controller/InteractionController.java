package com.linblog.module.comment.controller;

import com.linblog.common.ApiResult;
import com.linblog.module.comment.dto.InteractionRequest;
import com.linblog.module.comment.dto.PraiseResult;
import com.linblog.module.comment.service.InteractionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内容/日记点赞（§5.5 POST /interactions）：toggle，登录才可。
 * 游客（无主体）→ 40100；仅允许已发布目标，否则 40400 防探测（逻辑见 InteractionService）。
 */
@RestController
public class InteractionController {

    private final InteractionService interactionService;

    public InteractionController(InteractionService interactionService) {
        this.interactionService = interactionService;
    }

    /** POST /interactions {targetType:content|diary, targetId} → {active, count}。 */
    @PostMapping("/api/v1/interactions")
    public ApiResult<PraiseResult> toggle(@Valid @RequestBody InteractionRequest body) {
        return ApiResult.ok(interactionService.toggle(body));
    }
}
