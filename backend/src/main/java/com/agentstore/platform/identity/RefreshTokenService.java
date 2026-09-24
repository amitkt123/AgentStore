package com.agentstore.platform.identity;

import com.agentstore.platform.common.TokenHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Rotating refresh tokens. Each use replaces the token with a new one in the same family. Presenting a token
 * that was already replaced means a copy leaked, so the whole family is revoked.
 */
@Service
public class RefreshTokenService {

    private final RefreshTokenRepository repository;
    private final Duration ttl;

    public RefreshTokenService(RefreshTokenRepository repository,
                               @Value("${agentstore.identity.refresh-token-ttl:P30D}") Duration ttl) {
        this.repository = repository;
        this.ttl = ttl;
    }

    public record Rotation(UUID userId, String newRawToken) {}

    @Transactional
    public String issue(UUID userId) {
        return issueInFamily(userId, UUID.randomUUID());
    }

    /** Commits the reuse revocation even though the caller then gets a 401. */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Rotation rotate(String raw) {
        Instant now = Instant.now();
        RefreshToken current = repository.findByTokenHashForUpdate(TokenHasher.sha256Hex(raw))
                .orElseThrow(RefreshTokenService::unauthorized);
        if (current.getRevokedAt() != null) {
            repository.revokeFamily(current.getFamilyId(), now);
            throw unauthorized();
        }
        if (!current.getExpiresAt().isAfter(now)) {
            throw unauthorized();
        }
        current.setRevokedAt(now);
        repository.save(current);
        return new Rotation(current.getUserId(), issueInFamily(current.getUserId(), current.getFamilyId()));
    }

    @Transactional
    public void revokeFamilyOf(String raw) {
        repository.findByTokenHashForUpdate(TokenHasher.sha256Hex(raw))
                .ifPresent(t -> repository.revokeFamily(t.getFamilyId(), Instant.now()));
    }

    @Transactional
    public void revokeAllFor(UUID userId) {
        repository.revokeAllForUser(userId, Instant.now());
    }

    private String issueInFamily(UUID userId, UUID familyId) {
        String raw = TokenHasher.newToken();
        repository.save(new RefreshToken(userId, familyId, TokenHasher.sha256Hex(raw), Instant.now().plus(ttl)));
        return raw;
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session expired, sign in again");
    }
}
