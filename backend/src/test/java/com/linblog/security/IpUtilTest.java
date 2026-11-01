package com.linblog.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IpUtilTest {

    @Test
    void prefersFirstXffSegment() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.2");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        assertEquals("203.0.113.7", IpUtil.clientIp(request));
    }

    @Test
    void trimsWhitespace() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "  203.0.113.9 ");
        assertEquals("203.0.113.9", IpUtil.clientIp(request));
    }

    @Test
    void fallsBackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.5");
        assertEquals("192.168.1.5", IpUtil.clientIp(request));
    }

    @Test
    void blankXffFallsBack() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "  ");
        assertEquals("127.0.0.1", IpUtil.clientIp((HttpServletRequest) request));
    }

    @Test
    void xffCommaOnlySegmentsSkipped() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.16.0.9");
        request.addHeader("X-Forwarded-For", ",, ");
        assertEquals("172.16.0.9", IpUtil.clientIp(request));
        assertNull(null);
    }
}
