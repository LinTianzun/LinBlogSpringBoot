package com.linblog.module.audit.filter;

import com.linblog.module.audit.mapper.RequestAuditMapper;
import com.linblog.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/** 请求审计过滤器：requestId 进 MDC 与响应头，异步落库不阻塞。 */
class RequestAuditFilterTest {

    private RequestAuditMapper mapper;
    private RequestAuditFilter filter;

    private static class Chain implements jakarta.servlet.FilterChain {
        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            // 模拟业务：带认证主体
            SecurityContextHolder.getContext().setAuthentication(
                    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                            new AuthPrincipal(7L, "u", "viewer", false, Set.of()), null, null));
        }
    }

    @BeforeEach
    void setUp() {
        mapper = mock(RequestAuditMapper.class);
        filter = new RequestAuditFilter(mapper);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void writesAuditRowWithRequestIdAndPrincipal() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRequestURI("/api/v1/auth/login");
        request.setRemoteAddr("9.9.9.9");
        request.addHeader("User-Agent", "JUnit/1.0");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new Chain());

        String requestId = response.getHeader("X-Request-Id");
        assertTrue(requestId != null && requestId.length() == 32 && requestId.matches("[0-9a-f]+"),
                "requestId 为 32 位十六进制");
        assertNull(MDC.get("requestId"), "请求结束后 MDC 必须清理");

        ArgumentCaptor<com.linblog.module.audit.entity.RequestAudit> captor =
                ArgumentCaptor.forClass(com.linblog.module.audit.entity.RequestAudit.class);
        verify(mapper, timeout(2000)).insert(captor.capture());
        var row = captor.getValue();
        assertEquals(requestId, row.getRequestId());
        assertEquals(7L, row.getUserId());
        assertEquals("POST", row.getMethod());
        assertEquals("/api/v1/auth/login", row.getPath());
        assertEquals(200, row.getStatus());
        assertEquals("9.9.9.9", row.getIp());
        assertEquals("JUnit/1.0", row.getUa());
        assertTrue(row.getDurationMs() >= 0);
    }

    @Test
    void auditFailureDoesNotBreakResponse() throws Exception {
        Mockito.doThrow(new RuntimeException("db down")).when(mapper)
                .insert(Mockito.any(com.linblog.module.audit.entity.RequestAudit.class));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.setRequestURI("/x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new Chain());

        assertEquals(200, response.getStatus());
        assertTrue(response.getHeader("X-Request-Id") != null);
        Thread.sleep(300); // 给异步写失败留时间，确认没有逃逸异常
    }

    @Test
    void anonymousRequestHasNullUser() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/portal/contents");
        request.setRequestURI("/api/v1/portal/contents");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        ArgumentCaptor<com.linblog.module.audit.entity.RequestAudit> captor =
                ArgumentCaptor.forClass(com.linblog.module.audit.entity.RequestAudit.class);
        verify(mapper, timeout(2000)).insert(captor.capture());
        assertNull(captor.getValue().getUserId());
        assertFalse(captor.getValue().getRequestId().isEmpty());
    }
}
