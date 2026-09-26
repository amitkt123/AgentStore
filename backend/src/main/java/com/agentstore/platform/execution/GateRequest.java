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
}
