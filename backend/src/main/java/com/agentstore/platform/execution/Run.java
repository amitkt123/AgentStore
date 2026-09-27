package com.agentstore.platform.execution;

import com.agentstore.platform.catalog.AgentVersion;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "run")
@Getter
@Setter
@NoArgsConstructor
public class Run {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "connection_id")
    private Connection connection;

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_version_id")
    private AgentVersion agentVersion;

    @Enumerated(EnumType.STRING)
    private RunStatus status;

    private BigDecimal costCap;
    private BigDecimal totalCharged;
    private BigDecimal reservedAmount = BigDecimal.ZERO;
    private String sandboxId;
    private String callbackTokenHash;
    private Instant startedAt;
    private Instant completedAt;
    private Instant deadlineAt;
    private Instant lastHeartbeatAt;
    private Instant awaitingSince;

    @Enumerated(EnumType.STRING)
    private RunMode mode = RunMode.PRODUCTION;

    private int violationCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_json", columnDefinition = "jsonb")
    private String inputJson = "{}";

    /**
     * Read-only here on purpose: these counters change only through the gateway's atomic statements
     * ({@code RunRepository.reserveLlmTokens/settleLlmTokens}). A full entity save must never write a stale copy back.
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "llm_tokens_used", insertable = false, updatable = false)
    private int llmTokensUsed;

    @Setter(AccessLevel.NONE)
    @Column(name = "llm_tokens_reserved", insertable = false, updatable = false)
    private int llmTokensReserved;

    /** MOCK or REPLAY for a non-production run whose LLM calls are not real; null means real. Set by WS6. */
    @Enumerated(EnumType.STRING)
    @Column(name = "llm_mode")
    private RunLlmMode llmMode;

    private UUID replayRunId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "llm_mock_json", columnDefinition = "jsonb")
    private String llmMockJson;

    public Run(UUID id, Connection connection, AgentVersion agentVersion, RunStatus status, BigDecimal costCap,
               BigDecimal totalCharged, String sandboxId, String callbackTokenHash) {
        this.id = id;
        this.connection = connection;
        this.agentVersion = agentVersion;
        this.status = status;
        this.costCap = costCap;
        this.totalCharged = totalCharged;
        this.sandboxId = sandboxId;
        this.callbackTokenHash = callbackTokenHash;
    }
}
