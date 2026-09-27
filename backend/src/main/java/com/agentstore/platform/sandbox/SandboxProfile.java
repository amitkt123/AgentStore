package com.agentstore.platform.sandbox;

import com.agentstore.platform.execution.RunLlmMode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A developer's saved way of running one agent in the sandbox: input, gate policy, LLM mode, cap, fake secrets. */
@Entity
@Table(name = "sandbox_profile")
@Getter
@Setter
@NoArgsConstructor
public class SandboxProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID agentId;
    private String name;

    /** A one-off profile created by {@code POST /sandbox/runs}; listings exclude it. */
    private boolean inline;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_json", columnDefinition = "jsonb")
    private String inputJson = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "gate_policy", columnDefinition = "jsonb")
    private String gatePolicy = "{\"default\":\"MANUAL\",\"actions\":{}}";

    /** MOCK or REPLAY; null means real LLM calls. */
    @Enumerated(EnumType.STRING)
    @Column(name = "llm_mode")
    private RunLlmMode llmMode;

    private UUID replayRunId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "llm_mock_json", columnDefinition = "jsonb")
    private String llmMockJson;

    /** Null means the manifest's {@code costEnvelope.maxPerRun}. */
    private BigDecimal spendCap;

    private byte[] fakeSecretsEncrypted;
    private byte[] fakeSecretsNonce;
    private Instant createdAt;
    private Instant updatedAt;

    public SandboxProfile(UUID agentId, String name) {
        this.agentId = agentId;
        this.name = name;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
