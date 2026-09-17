package com.agentstore.platform.ledger;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usage_event")
@Getter
@Setter
@NoArgsConstructor
public class UsageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID runId;
    private UUID orgId;
    private BigDecimal quantity;
    private BigDecimal unitCost;
    private BigDecimal amount;
    private boolean processed;
    private Instant createdAt;

    public UsageEvent(UUID id, UUID runId, UUID orgId, BigDecimal quantity, BigDecimal unitCost, BigDecimal amount) {
        this.id = id;
        this.runId = runId;
        this.orgId = orgId;
        this.quantity = quantity;
        this.unitCost = unitCost;
        this.amount = amount;
        this.processed = false;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
