package com.linblog.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linblog.support.TestAppProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §7 双提交 XSRF 过滤器：非 GET 必须带匹配的 X-XSRF-TOKEN 头与 Cookie；Origin 白名单。 */
class XsrfFilterTest {

    private XsrfFilter filter;
    private RecordingChain chain;

    private static class RecordingChain implements FilterChain {
        boolean invoked;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            invoked = true;
        }
    }

    @BeforeEach
    void setUp() {
        filter = new XsrfFilter(TestAppProperties.defaults(), new ObjectMapper());
        chain = new RecordingChain();
    }

    private void run(MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(), chain);
    }

    private MockHttpServletRequest post() {
        return new MockHttpServletRequest("POST", "/api/v1/auth/login");
    }

    @Test
    void getWithoutCookieSeedsXsrfCookie() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/portal/contents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertTrue(chain.invoked);
        String setCookie = response.getHeader("Set-Cookie");
        assertTrue(setCookie != null && setCookie.startsWith("XSRF-TOKEN="), setCookie);
        assertTrue(setCookie.contains("Path=/"));
    }

    @Test
    void matchingHeaderAndCookiePasses() throws Exception {
        MockHttpServletRequest request = post();
        request.setCookies(new jakarta.servlet.http.Cookie(CookieUtil.XSRF_COOKIE, "tok-1"));
        request.addHeader(CookieUtil.XSRF_HEADER, "tok-1");
        run(request);
        assertTrue(chain.invoked);
    }

    @Test
    void missingHeaderRejected403() throws Exception {
        MockHttpServletRequest request = post();
        request.setCookies(new jakarta.servlet.http.Cookie(CookieUtil.XSRF_COOKIE, "tok-1"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("40300"));
        assertTrue(response.getContentAsString().contains("X-XSRF-TOKEN"));
    }

    @Test
    void mismatchedHeaderRejected() throws Exception {
        MockHttpServletRequest request = post();
        request.setCookies(new jakarta.servlet.http.Cookie(CookieUtil.XSRF_COOKIE, "tok-1"));
        request.addHeader(CookieUtil.XSRF_HEADER, "tok-other");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertEquals(403, response.getStatus());
    }

    @Test
    void foreignOriginRejectedEvenWithValidToken() throws Exception {
        MockHttpServletRequest request = post();
        request.addHeader("Origin", "http://evil.example.com");
        request.setCookies(new jakarta.servlet.http.Cookie(CookieUtil.XSRF_COOKIE, "tok-1"));
        request.addHeader(CookieUtil.XSRF_HEADER, "tok-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("跨站来源被拒绝"));
    }

    @Test
    void allowedOriginAccepted() throws Exception {
        MockHttpServletRequest request = post();
        request.addHeader("Origin", "http://localhost:5173");
        request.setCookies(new jakarta.servlet.http.Cookie(CookieUtil.XSRF_COOKIE, "tok-1"));
        request.addHeader(CookieUtil.XSRF_HEADER, "tok-1");
        run(request);
        assertTrue(chain.invoked);
    }

    @Test
    void bearerAuthExemptFromDoubleSubmit() throws Exception {
        MockHttpServletRequest request = post();
        request.setAttribute(JwtCookieFilter.ATTR_BEARER, Boolean.TRUE);
        run(request);
        assertTrue(chain.invoked);
    }

    @Test
    void safeMethodsOtherThanGetAlsoExempt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/anything");
        run(request);
        assertTrue(chain.invoked);
    }
}
