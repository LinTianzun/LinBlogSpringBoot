package com.linblog.security;

import com.linblog.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * 访问令牌（§7.1）：HS256，claims = sub(uid) / tv(token_version) / role。
 * TTL 默认 PT15M（JWT_ACCESS_TTL）。
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(AppProperties props) {
        this.key = Keys.hmacShaKeyFor(props.jwtAccessSecret().getBytes(StandardCharsets.UTF_8));
        this.ttl = props.jwtAccessTtl();
    }

    public String issue(long uid, int tokenVersion, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(Long.toString(uid))
                .claim("tv", tokenVersion)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 验签并解析；过期抛 ExpiredJwtException，无效抛 JwtException。 */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public Duration ttl() {
        return ttl;
    }
}
