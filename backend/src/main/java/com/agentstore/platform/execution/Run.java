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

    /** Null for a sandbox run: it has no buyer, so no connection (see {@code initiatedByOrgId}). */
    @ManyToOne
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

    /** The developer org that started a SANDBOX run; null for every other mode. */
    private UUID initiatedByOrgId;

    private UUID sandboxProfileId;

    /** 'R-12' or 'S-4'; assigned by the run_display_id trigger and read back after insert. */
    @Setter(AccessLevel.NONE)
    @org.hibernate.annotations.Generated(event = org.hibernate.generator.EventType.INSERT)
    @Column(name = "display_id", insertable = false, updatable = false)
    private String displayId;

    @Enumerated(EnumType.STRING)
    @Column(name = "terminal_reason")
    private TerminalReason terminalReason;

    /** The block of the most recent block-carrying step; kept in step with FlowView.reachedBlock. */
    private String reachedBlock;

    /** The closing step's summary, for the run list search. */
    @Column(columnDefinition = "text")
    private String summary;

    /** What a SANDBOX run's usage reports would have cost after the cap. No money moves. */
    private BigDecimal simulatedCharged = BigDecimal.ZERO;

    /** The un-clipped amount those usage reports asked for. */
    private BigDecimal requestedCharge = BigDecimal.ZERO;

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
