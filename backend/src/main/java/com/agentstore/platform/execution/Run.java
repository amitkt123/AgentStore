package com.agentstore.platform.execution;

import com.agentstore.platform.catalog.AgentVersion;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
