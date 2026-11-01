package com.linblog.security;

import com.linblog.support.TestAppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService(TestAppProperties.defaults());

    @Test
    void issueAndParseRoundtrip() {
        String token = jwtService.issue(42L, 3, "editor");
        Claims claims = jwtService.parse(token);
        assertEquals("42", claims.getSubject());
        assertEquals(3, ((Number) claims.get("tv")).intValue());
        assertEquals("editor", claims.get("role", String.class));
    }

    @Test
    void tamperedTokenRejected() {
        String token = jwtService.issue(1L, 0, "viewer");
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        assertThrows(JwtException.class, () -> jwtService.parse(tampered));
    }

    @Test
    void garbageTokenRejected() {
        assertThrows(JwtException.class, () -> jwtService.parse("not.a.jwt"));
    }

    @Test
    void expiredTokenThrowsExpired() throws InterruptedException {
        JwtService shortTtl = new JwtService(TestAppProperties.of(new Object[]{3, Duration.ofMillis(50)}));
        String token = shortTtl.issue(1L, 0, "viewer");
        Thread.sleep(150);
        assertThrows(ExpiredJwtException.class, () -> shortTtl.parse(token));
    }

    @Test
    void differentSecretCannotVerify() {
        JwtService other = new JwtService(TestAppProperties.of(new Object[]{
                2, "fedcba9876543210fedcba9876543210"}));
        String token = jwtService.issue(1L, 0, "viewer");
        assertThrows(JwtException.class, () -> other.parse(token));
        assertTrue(jwtService.ttl().toMinutes() == 15);
    }
}
