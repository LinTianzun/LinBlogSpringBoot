package com.linblog.module.audit.filter;

import com.linblog.module.audit.entity.RequestAudit;
import com.linblog.module.audit.mapper.RequestAuditMapper;
import com.linblog.security.CurrentUser;
import com.linblog.security.IpUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 请求审计过滤器（§7.6 前置）：生成 requestId（32 位）进 MDC 与 X-Request-Id 头，
 * 请求结束后异步（虚拟线程）写 request_audits。审计失败不影响响应。
 */
public class RequestAuditFilter extends OncePerRequestFilter {

    public static final String MDC_REQUEST_ID = "requestId";
    private static final Logger log = LoggerFactory.getLogger(RequestAuditFilter.class);
    private static final ExecutorService AUDIT_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final RequestAuditMapper requestAuditMapper;

    public RequestAuditFilter(RequestAuditMapper requestAuditMapper) {
        this.requestAuditMapper = requestAuditMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.isAsyncDispatch()) {
            chain.doFilter(request, response);
            return;
        }
        String requestId = UUID.randomUUID().toString().replace("-", "");
        long startNanos = System.nanoTime();
        MDC.put(MDC_REQUEST_ID, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            try {
                response.setHeader("X-Request-Id", requestId);
                writeAudit(request, response, requestId, (System.nanoTime() - startNanos) / 1_000_000);
            } finally {
                MDC.remove(MDC_REQUEST_ID);
            }
        }
    }

    private void writeAudit(HttpServletRequest request, HttpServletResponse response, String requestId, long durationMs) {
        var principal = CurrentUser.get();
        RequestAudit row = new RequestAudit();
        row.setRequestId(requestId);
        row.setUserId(principal == null ? null : principal.uid());
        row.setMethod(truncate(request.getMethod(), 10));
        row.setPath(truncate(request.getRequestURI(), 200));
        row.setStatus(response.getStatus());
        row.setIp(truncate(IpUtil.clientIp(request), 45));
        row.setUa(truncate(request.getHeader("User-Agent"), 300));
        row.setDurationMs((int) Math.min(durationMs, Integer.MAX_VALUE));
        AUDIT_EXECUTOR.execute(() -> {
            try {
                requestAuditMapper.insert(row);
            } catch (RuntimeException e) {
                log.debug("request_audits 写入失败: {}", e.getMessage());
            }
        });
    }

    private static String truncate(String s, int max) {
        return s == null ? null : s.substring(0, Math.min(s.length(), max));
    }
}
