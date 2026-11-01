package com.linblog.security;

import com.linblog.module.permission.service.PermissionService;
import com.linblog.support.TestAppProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** §7.1 认证过滤器：Cookie 优先、Bearer 兼容、tv/status 校验、authorities 注入。 */
class JwtCookieFilterTest {

    private JwtCookieFilter filter;
    private UserAuthStateService authStateService;
    private FilterChainChain chain;

    /** 记录链是否放行的简单桩。 */
    private static class FilterChainChain implements jakarta.servlet.FilterChain {
        boolean invoked;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            invoked = true;
        }
    }

    @BeforeEach
    void setUp() {
        JwtService jwtService = new JwtService(TestAppProperties.defaults());
        authStateService = mock(UserAuthStateService.class);
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.keysForRole(anyString())).thenReturn(Set.of("article.viewAll"));
        filter = new JwtCookieFilter(jwtService, authStateService, permissionService);
        chain = new FilterChainChain();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/api/v1/auth/me");
    }

    private void run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
    }

    @Test
    void validCookieAuthenticates() throws Exception {
        String token = new JwtService(TestAppProperties.defaults()).issue(7L, 2, "editor");
        when(authStateService.load(7L)).thenReturn(
                new UserAuthStateService.AuthState(true, 2, "alice", "editor"));

        MockHttpServletRequest request = request();
        request.setCookies(new jakarta.servlet.http.Cookie(CookieUtil.ACCESS_COOKIE, token));
        run(request);

        assertTrue(chain.invoked);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        assertEquals(7L, principal.uid());
        assertEquals("alice", principal.username());
        assertEquals("editor", principal.role());
        assertFalse(principal.superAdmin());
        assertTrue(principal.has("article.viewAll"));
        assertTrue(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_editor")));
        assertTrue(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("article.viewAll")));
        assertNull(request.getAttribute(JwtCookieFilter.ATTR_BEARER));
        assertNull(request.getAttribute(JwtCookieFilter.ATTR_EXPIRED));
    }

    @Test
    void bearerHeaderAlsoWorksAndSetsAttr() throws Exception {
        String token = new JwtService(TestAppProperties.defaults()).issue(8L, 0, "admin");
        when(authStateService.load(8L)).thenReturn(
                new UserAuthStateService.AuthState(true, 0, "root", "super_admin"));

        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer " + token);
        run(request);

        AuthPrincipal principal = (AuthPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertTrue(principal.superAdmin(), "DB roleCode=super_admin → superAdmin=true");
        assertEquals(Boolean.TRUE, request.getAttribute(JwtCookieFilter.ATTR_BEARER));
    }

    @Test
    void cookieTakesPrecedenceOverHeader() throws Exception {
        String cookieToken = new JwtService(TestAppProperties.defaults()).issue(1L, 0, "editor");
        String headerToken = new JwtService(TestAppProperties.defaults()).issue(2L, 0, "viewer");
        when(authStateService.load(1L)).thenReturn(
                new UserAuthStateService.AuthState(true, 0, "u1", "editor"));

        MockHttpServletRequest request = request();
        request.setCookies(new jakarta.servlet.http.Cookie(CookieUtil.ACCESS_COOKIE, cookieToken));
        request.addHeader("Authorization", "Bearer " + headerToken);
        run(request);

        assertEquals(1L, ((AuthPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).uid());
    }

    @Test
    void disabledUserStaysAnonymous() throws Exception {
        String token = new JwtService(TestAppProperties.defaults()).issue(9L, 0, "editor");
        when(authStateService.load(9L)).thenReturn(
                new UserAuthStateService.AuthState(false, 0, "bad", "editor"));

        MockHttpServletRequest request = request();
        request.setCookies(new jakarta.servlet.http.Cookie(CookieUtil.ACCESS_COOKIE, token));
        run(request);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(chain.invoked);
    }

    @Test
    void tokenVersionMismatchStaysAnonymous() throws Exception {
        String token = new JwtService(TestAppProperties.defaults()).issue(10L, 5, "editor");
        when(authStateService.load(10L)).thenReturn(
                new UserAuthStateService.AuthState(true, 6, "u", "editor"));

        MockHttpServletRequest request = request();
        request.setCookies(new jakarta.servlet.http.Cookie(CookieUtil.ACCESS_COOKIE, token));
        run(request);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void expiredTokenSetsExpiredAttr() throws Exception {
        JwtService shortTtl = new JwtService(TestAppProperties.of(new Object[]{3, Duration.ofMillis(50)}));
        String token = shortTtl.issue(11L, 0, "viewer");
        Thread.sleep(150);

        MockHttpServletRequest request = request();
        request.setCookies(new jakarta.servlet.http.Cookie(CookieUtil.ACCESS_COOKIE, token));
        run(request);

        assertEquals(Boolean.TRUE, request.getAttribute(JwtCookieFilter.ATTR_EXPIRED));
        assertEquals(com.linblog.common.ErrorCode.ACCESS_EXPIRED, JwtCookieFilter.unauthorizedCode(request));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void garbageTokenStaysAnonymousAndReportsUnauthenticated() throws Exception {
        MockHttpServletRequest request = request();
        request.setCookies(new jakarta.servlet.http.Cookie(CookieUtil.ACCESS_COOKIE, "garbage.token.here"));
        run(request);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(com.linblog.common.ErrorCode.UNAUTHENTICATED, JwtCookieFilter.unauthorizedCode(request));
    }

    @Test
    void noTokenNoAuthentication() throws Exception {
        MockHttpServletRequest request = request();
        run(request);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(chain.invoked);
    }
}
