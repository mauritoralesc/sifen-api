package com.ratones.sifenwrapper.security.jwt;

import com.ratones.sifenwrapper.config.SecurityProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

@Slf4j
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long accessTokenExpiration;
    private final long refreshTokenExpiration;
    private static final long SELECTION_TOKEN_EXPIRATION = 300_000L; // 5 minutes

    public JwtService(SecurityProperties properties) {
        String secret = properties.getJwt().getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("security.jwt.secret must be set");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiration = properties.getJwt().getAccessTokenExpiration() * 1000;
        this.refreshTokenExpiration = properties.getJwt().getRefreshTokenExpiration() * 1000;
    }

    public String generateAccessToken(Long userId, Long companyId, String role) {
        return buildToken(Map.of(
                "companyId", companyId,
                "role", role,
                "type", "access"
        ), String.valueOf(userId), accessTokenExpiration);
    }

    public String generateRefreshToken(Long userId, Long companyId) {
        return buildToken(Map.of(
                "type", "refresh",
                "companyId", companyId
        ), String.valueOf(userId), refreshTokenExpiration);
    }

    public String generateSelectionToken(Long userId) {
        return buildToken(Map.of("type", "selection"), String.valueOf(userId), SELECTION_TOKEN_EXPIRATION);
    }

    public String generateNoCompanyToken(Long userId, String role) {
        return buildToken(Map.of(
                "role", role,
                "type", "access"
        ), String.valueOf(userId), accessTokenExpiration);
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValidToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid JWT: {}", e.getMessage());
            return false;
        }
    }

    public Long getUserId(String token) {
        return Long.valueOf(parseToken(token).getSubject());
    }

    public Long getCompanyId(String token) {
        return parseToken(token).get("companyId", Long.class);
    }

    public String getRole(String token) {
        return parseToken(token).get("role", String.class);
    }

    public String getTokenType(String token) {
        return parseToken(token).get("type", String.class);
    }

    private String buildToken(Map<String, Object> claims, String subject, long expirationMs) {
        Date now = new Date();
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(signingKey)
                .compact();
    }
}
