package com.linblog.security;

import com.linblog.support.TestAppProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §4.3/§7.2 Cookie 约定：三枚 Cookie 的属性与清除。 */
class CookieUtilTest {

    private final CookieUtil cookieUtil = new CookieUtil(TestAppProperties.defaults());

    @Test
    void accessCookieAttributes() {
        var c = cookieUtil.accessCookie("jwt-value");
        assertEquals(CookieUtil.ACCESS_COOKIE, c.getName());
        assertEquals("jwt-value", c.getValue());
        assertTrue(c.isHttpOnly());
        assertFalse(c.isSecure());
        assertEquals("Lax", c.getSameSite());
        assertEquals("/", c.getPath());
        assertEquals(15 * 60, c.getMaxAge().toSeconds());
    }

    @Test
    void refreshCookieAttributes() {
        var c = cookieUtil.refreshCookie("raw-refresh");
        assertEquals(CookieUtil.REFRESH_COOKIE, c.getName());
        assertTrue(c.isHttpOnly());
        assertEquals("Strict", c.getSameSite());
        assertEquals("/api/v1/auth", c.getPath());
        assertEquals(7, c.getMaxAge().toDays());
    }

    @Test
    void xsrfCookieNotHttpOnly() {
        var c = cookieUtil.xsrfCookie("xsrf-123");
        assertEquals(CookieUtil.XSRF_COOKIE, c.getName());
        assertFalse(c.isHttpOnly(), "XSRF 双提交令牌必须可被 JS 读取");
        assertEquals("Lax", c.getSameSite());
        assertEquals("/", c.getPath());
        assertEquals(7, c.getMaxAge().toDays());
    }

    @Test
    void clearCookiesUseMaxAgeZero() {
        assertEquals(0, cookieUtil.clearAccess().getMaxAge().getSeconds());
        assertEquals(CookieUtil.ACCESS_COOKIE, cookieUtil.clearAccess().getName());
        assertEquals(0, cookieUtil.clearRefresh().getMaxAge().getSeconds());
        assertEquals(CookieUtil.REFRESH_COOKIE, cookieUtil.clearRefresh().getName());
        assertEquals("/api/v1/auth", cookieUtil.clearRefresh().getPath());
    }

    @Test
    void secureFlagFollowsProps() {
        CookieUtil secure = new CookieUtil(TestAppProperties.of(new Object[]{6, true}));
        assertTrue(secure.accessCookie("x").isSecure());
        assertTrue(secure.refreshCookie("x").isSecure());
        assertTrue(secure.xsrfCookie("x").isSecure());
    }

    @Test
    void cookieDomainAppliedWhenPresent() {
        CookieUtil domained = new CookieUtil(TestAppProperties.of(new Object[]{5, "linlog.com"}));
        assertEquals("linlog.com", domained.accessCookie("x").getDomain());
    }

    @Test
    void readFindsCookieOrReturnsNull() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertNull(CookieUtil.read(request, CookieUtil.ACCESS_COOKIE));
        request.setCookies(new Cookie("other", "1"), new Cookie(CookieUtil.ACCESS_COOKIE, "t"));
        assertEquals("t", CookieUtil.read(request, CookieUtil.ACCESS_COOKIE));
    }
}
