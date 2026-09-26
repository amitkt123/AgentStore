package com.agentstore.platform.data;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One agent write call: a JSONL chunk in the object store, staged until an approved gate commits it. */
@Entity
@Table(name = "run_output")
@Getter
@Setter
@NoArgsConstructor
public class RunOutput {

    @Id
    private UUID id;

    private UUID runId;
    private String resourceName;

    @Enumerated(EnumType.STRING)
    private OutputStatus status;

    private int recordCount;
    private long sizeBytes;
    private String storageKey;
    private UUID gateRequestId;
    private Instant createdAt;

    /** The id is chosen by the caller because the blob key {@code outputs/{runId}/{id}.jsonl} is written first. */
    public RunOutput(UUID id, UUID runId, String resourceName, OutputStatus status, int recordCount, long sizeBytes,
                     String storageKey) {
        this.id = id;
        this.runId = runId;
        this.resourceName = resourceName;
        this.status = status;
        this.recordCount = recordCount;
        this.sizeBytes = sizeBytes;
        this.storageKey = storageKey;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
