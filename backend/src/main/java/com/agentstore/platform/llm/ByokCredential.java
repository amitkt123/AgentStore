package com.agentstore.platform.llm;

import com.agentstore.platform.manifest.LlmVendor;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A developer org's LLM key for one provider, AES-GCM encrypted with the same key as connection secrets. */
@Entity
@Table(name = "byok_credential")
@Getter
@Setter
@NoArgsConstructor
public class ByokCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID developerOrgId;

    @Enumerated(EnumType.STRING)
    private LlmVendor provider;

    private byte[] encryptedKey;
    private byte[] nonce;
    private String last4;
    private Instant createdAt;

    public ByokCredential(UUID developerOrgId, LlmVendor provider) {
        this.developerOrgId = developerOrgId;
        this.provider = provider;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
