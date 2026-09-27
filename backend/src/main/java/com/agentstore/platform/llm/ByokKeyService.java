package com.agentstore.platform.llm;

import com.agentstore.platform.common.AesGcmCipher;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.manifest.LlmVendor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A developer org's own LLM keys. Stored AES-GCM encrypted with the same key as connection secrets; only
 * {@link #reveal} returns the plaintext, and only the gateway and the launcher call it.
 */
@Service
public class ByokKeyService {

    static final int MAX_KEY_LENGTH = 512;

    public record Summary(String provider, String last4, Instant createdAt) {
    }

    private final ByokCredentialRepository credentials;
    private final OrgRepository orgs;
    private final AesGcmCipher cipher;

    public ByokKeyService(ByokCredentialRepository credentials, OrgRepository orgs, AesGcmCipher cipher) {
        this.credentials = credentials;
        this.orgs = orgs;
        this.cipher = cipher;
    }

    /** Stores or replaces the org's key for the provider. Does not test the key against the provider. */
    @Transactional
    public Summary put(UUID orgId, LlmVendor vendor, String apiKey) {
        Org org = orgs.findById(orgId)
                .orElseThrow(() -> new LlmApiException(HttpStatus.NOT_FOUND, "ORG_NOT_FOUND", "Organisation not found"));
        if (org.getDeveloperVerifiedAt() == null) {
            throw notVerified();
        }
        validate(apiKey);
        AesGcmCipher.Encrypted encrypted = cipher.encrypt(apiKey.getBytes(StandardCharsets.UTF_8));
        ByokCredential credential = credentials.findByDeveloperOrgIdAndProvider(orgId, vendor)
                .orElseGet(() -> new ByokCredential(orgId, vendor));
        credential.setEncryptedKey(encrypted.ciphertext());
        credential.setNonce(encrypted.nonce());
        credential.setLast4(last4(apiKey));
        credential.setCreatedAt(Instant.now());
        return summary(credentials.save(credential));
    }

    @Transactional(readOnly = true)
    public List<Summary> list(UUID orgId) {
        return credentials.findByDeveloperOrgIdOrderByProviderAsc(orgId).stream().map(ByokKeyService::summary).toList();
    }

    /** Idempotent: deleting a key that is not there is not an error. Allowed for any org (clean-up). */
    @Transactional
    public void delete(UUID orgId, LlmVendor vendor) {
        credentials.deleteByDeveloperOrgIdAndProvider(orgId, vendor);
    }

    /** The plaintext key. Never log it, return it, or put it in an exception message. */
    @Transactional(readOnly = true)
    public Optional<String> reveal(UUID orgId, LlmVendor vendor) {
        return credentials.findByDeveloperOrgIdAndProvider(orgId, vendor)
                .map(c -> new String(cipher.decrypt(c.getEncryptedKey(), c.getNonce()), StandardCharsets.UTF_8));
    }

    static LlmApiException notVerified() {
        return new LlmApiException(HttpStatus.FORBIDDEN, "BYOK_NOT_VERIFIED",
                "Only verified developer organisations can bring their own LLM key");
    }

    private static void validate(String apiKey) {
        if (apiKey == null || apiKey.isBlank() || apiKey.length() > MAX_KEY_LENGTH
                || apiKey.chars().anyMatch(Character::isWhitespace)) {
            throw new LlmApiException(HttpStatus.BAD_REQUEST, "INVALID_API_KEY",
                    "apiKey must be a non-blank value without whitespace, at most " + MAX_KEY_LENGTH + " characters");
        }
    }

    /** The last four characters, unless the key is so short that they would give most of it away. */
    private static String last4(String apiKey) {
        return apiKey.length() < 8 ? "****" : apiKey.substring(apiKey.length() - 4);
    }

    private static Summary summary(ByokCredential credential) {
        return new Summary(credential.getProvider().wire(), credential.getLast4(), credential.getCreatedAt());
    }
}
