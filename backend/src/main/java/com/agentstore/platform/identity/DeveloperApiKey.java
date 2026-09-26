package com.agentstore.platform.identity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One API key. Only the hash of the key is kept; {@code prefix} is what the portal lists. */
@Entity
@Table(name = "developer_api_key")
@Getter
@Setter
@NoArgsConstructor
public class DeveloperApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID orgId;
    private UUID createdByUserId;
    private String name;
    private String prefix;
    private String keyHash;
    private Instant createdAt;
    private Instant lastUsedAt;
    private Instant revokedAt;

    public DeveloperApiKey(UUID orgId, UUID createdByUserId, String name, String prefix, String keyHash) {
        this.orgId = orgId;
        this.createdByUserId = createdByUserId;
        this.name = name;
        this.prefix = prefix;
        this.keyHash = keyHash;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
