package com.agentstore.platform.identity;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DeveloperApiKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeveloperApiKeyRepository keys;

    public DeveloperApiKeyService(DeveloperApiKeyRepository keys) {
        this.keys = keys;
    }

    /** The only place the plaintext key exists. It must never be logged, so toString hides it. */
    public record Created(UUID id, String name, String prefix, String key, Instant createdAt) {
        @Override
        public String toString() {
            return "Created[id=" + id + ", name=" + name + ", prefix=" + prefix + "]";
        }
    }

    public record Summary(UUID id, String name, String prefix, Instant createdAt, Instant lastUsedAt, Instant revokedAt) {
        static Summary from(DeveloperApiKey k) {
            return new Summary(k.getId(), k.getName(), k.getPrefix(), k.getCreatedAt(), k.getLastUsedAt(), k.getRevokedAt());
        }
    }

    @Transactional
    public Created create(UUID orgId, UUID userId, String name) {
        String key = ApiKeys.generate(RANDOM);
        DeveloperApiKey saved = keys.save(new DeveloperApiKey(orgId, userId, name.trim(),
                key.substring(0, ApiKeys.PREFIX_LENGTH), ApiKeys.hash(key)));
        return new Created(saved.getId(), saved.getName(), saved.getPrefix(), key, saved.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<Summary> list(UUID orgId) {
        return keys.findByOrgIdOrderByCreatedAtDesc(orgId).stream().map(Summary::from).toList();
    }

    @Transactional
    public void revoke(UUID orgId, UUID id) {
        DeveloperApiKey key = keys.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API key not found"));
        if (key.getRevokedAt() == null) {
            key.setRevokedAt(Instant.now());
        }
    }
}
