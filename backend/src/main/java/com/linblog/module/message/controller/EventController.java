package com.linblog.module.message.controller;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.message.service.SsePushService;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 订阅端点（§5.8，M6）：登录用户订阅实时事件（text/event-stream）。
 * 根路径 /events（契约冻结：frontend sse.ts BASE='/events'，Caddyfile @backend 直反代 + flush_interval -1）。
 * 登录由安全链兜底（anyRequest().authenticated()，匿名 → 40100 JSON 壳）；
 * 事件 message.new / report.new / heartbeat（30s，data: ping）。
 */
@RestController
public class EventController {

    private final SsePushService ssePushService;

    public EventController(SsePushService ssePushService) {
        this.ssePushService = ssePushService;
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return ssePushService.register(requirePrincipal());
    }

    private AuthPrincipal requirePrincipal() {
        AuthPrincipal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未登录");
        }
        return principal;
    }
}
