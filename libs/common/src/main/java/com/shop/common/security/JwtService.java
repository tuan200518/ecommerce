package com.shop.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Phát hành và xác thực JWT (HMAC-SHA256) dùng chung giữa Auth Service và API Gateway. */
public class JwtService {

    private static final String ISSUER = "shop-auth-service";

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(String secret, Duration ttl) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalArgumentException("JWT secret phải dài tối thiểu 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttl = ttl;
    }

    public String issue(long userId, String email, Set<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(Long.toString(userId))
                .claim("email", email)
                .claim("roles", List.copyOf(roles))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** @throws JwtException nếu token sai chữ ký, hết hạn hoặc không hợp lệ. */
    public AuthenticatedUser verify(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(ISSUER)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        Set<String> roles = new LinkedHashSet<>();
        Object raw = claims.get("roles");
        if (raw instanceof List<?> list) {
            list.forEach(r -> roles.add(String.valueOf(r)));
        }
        return new AuthenticatedUser(Long.parseLong(claims.getSubject()),
                claims.get("email", String.class), roles);
    }

    public Duration ttl() {
        return ttl;
    }
}
