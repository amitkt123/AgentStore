package com.agentstore.platform.identity;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMinutes;

    public JwtService(@Value("${agentstore.jwt.secret}") String secret,
                       @Value("${agentstore.jwt.expiration-minutes}") long expirationMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = expirationMinutes;
    }

    public String issue(UUID userId, UUID orgId, UserRole role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("orgId", orgId.toString())
                .claim("role", role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(expirationMinutes))))
                .signWith(key)
                .compact();
    }

    public Optional<Claims> parse(String token) {
        try {
            var payload = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return Optional.of(new Claims(
                    UUID.fromString(payload.getSubject()),
                    UUID.fromString(payload.get("orgId", String.class)),
                    UserRole.valueOf(payload.get("role", String.class))));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public record Claims(UUID userId, UUID orgId, UserRole role) {}
}
