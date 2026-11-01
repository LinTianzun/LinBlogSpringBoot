package com.linblog.module.stats.controller;

import com.linblog.common.ApiResult;
import com.linblog.module.stats.dto.ViewReportRequest;
import com.linblog.module.stats.service.ViewService;
import com.linblog.security.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 浏览上报（§5.6/§7.6）：公开；visitor_key 哈希不落明文；30 分钟窗口去重。
 */
@RestController
@RequestMapping("/api/v1/views")
public class ViewController {

    private final ViewService viewService;

    public ViewController(ViewService viewService) {
        this.viewService = viewService;
    }

    public record ViewReportResult(boolean counted) {
    }

    @PostMapping
    public ApiResult<ViewReportResult> report(@Valid @RequestBody ViewReportRequest body,
                                              HttpServletRequest request) {
        boolean counted = viewService.report(body.targetType(), body.targetId(),
                IpUtil.clientIp(request), request.getHeader("User-Agent"));
        return ApiResult.ok(new ViewReportResult(counted));
    }
}
