package com.agentstore.platform.ledger;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_entry")
@Getter
@Setter
@NoArgsConstructor
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID usageEventId;
    private UUID runId;
    private UUID orgId;
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private LedgerEntryType type;

    private Instant createdAt;

    public LedgerEntry(UUID id, UUID usageEventId, UUID runId, UUID orgId, BigDecimal amount, LedgerEntryType type) {
        this.id = id;
        this.usageEventId = usageEventId;
        this.runId = runId;
        this.orgId = orgId;
        this.amount = amount;
        this.type = type;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
