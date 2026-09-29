package com.agentstore.platform.execution;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** One approval request an agent raised through POST /runtime/v1/gates. */
@Entity
@Table(name = "gate_request")
@Getter
@Setter
@NoArgsConstructor
public class GateRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "run_id")
    private Run run;

    private String action;

    @Column(columnDefinition = "text")
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "affects_resources", columnDefinition = "jsonb")
    private String affectsResources = "[]";

    @Enumerated(EnumType.STRING)
    private GateStatus status = GateStatus.PENDING;

    private UUID decidedByUserId;

    @Column(columnDefinition = "text")
    private String decisionNote;

    private Instant createdAt;
    private Instant decidedAt;
    private Instant expiresAt;

    @Column(name = "estimated_units")
    private java.math.BigDecimal estimatedUnits;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String preview;

    @Enumerated(EnumType.STRING)
    private DecidedVia decidedVia;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "eligible_approver_ids", columnDefinition = "jsonb")
    private String eligibleApproverIdsJson;

    /** Null when the gate is unrestricted (sandbox runs, or a buyer org with no approver members). */
    public java.util.List<java.util.UUID> eligibleApproverIds() {
        if (eligibleApproverIdsJson == null) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(eligibleApproverIdsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.UUID>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Corrupt eligible_approver_ids on gate " + id, e);
        }
    }

    public GateRequest(Run run, String action, String summary, String payload, String affectsResources,
                       Instant expiresAt) {
        this.run = run;
        this.action = action;
        this.summary = summary;
        this.payload = payload;
        this.affectsResources = affectsResources;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    /** The resource names this gate covers, parsed from {@code affects_resources}. */
    public java.util.List<String> affected() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(affectsResources,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {});
        } catch (Exception e) {
            return java.util.List.of();
        }
    }
}
