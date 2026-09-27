package com.agentstore.platform.llm;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One call the gateway handled (served, failed, over budget or a replay miss). The id is chosen by the caller because
 * recorded blobs are keyed by it before the row is written. Never holds a prompt, a key or a provider body.
 */
@Entity
@Table(name = "llm_call")
@Getter
@Setter
@NoArgsConstructor
public class LlmCall {

    @Id
    private UUID id;

    private UUID runId;
    private String modelId;

    @Enumerated(EnumType.STRING)
    private LlmSource source;

    @Enumerated(EnumType.STRING)
    private LlmCallStatus status;

    private int inputTokens;
    private int outputTokens;
    private BigDecimal cost = BigDecimal.ZERO;
    private int latencyMs;
    private String requestHash;
    private Integer replayOrdinal;
    private String requestKey;
    private String responseKey;
    private Instant recordingExpiresAt;
    private Instant recordingPurgedAt;
    private String errorCode;
    private Instant createdAt;

    public LlmCall(UUID id, UUID runId, String modelId, LlmSource source, LlmCallStatus status, String requestHash) {
        this.id = id;
        this.runId = runId;
        this.modelId = modelId;
        this.source = source;
        this.status = status;
        this.requestHash = requestHash;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
