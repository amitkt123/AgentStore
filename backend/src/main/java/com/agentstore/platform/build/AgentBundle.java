package com.agentstore.platform.build;

import com.agentstore.platform.catalog.Agent;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** An uploaded code bundle. The id is chosen before insert because it is part of the object-store key. */
@Entity
@Table(name = "agent_bundle")
@Getter
@Setter
@NoArgsConstructor
public class AgentBundle {

    @Id
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id")
    private Agent agent;

    private UUID uploadedByUserId;
    private String storageKey;
    private String sha256;
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    private BundleStatus status;

    private Instant createdAt;

    public AgentBundle(UUID id, Agent agent, UUID uploadedByUserId, String storageKey, String sha256, long sizeBytes,
                       BundleStatus status) {
        this.id = id;
        this.agent = agent;
        this.uploadedByUserId = uploadedByUserId;
        this.storageKey = storageKey;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.status = status;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
