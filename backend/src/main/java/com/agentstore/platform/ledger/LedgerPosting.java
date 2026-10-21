package com.agentstore.platform.ledger;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_posting")
@Getter
@Setter
@NoArgsConstructor
public class LedgerPosting {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID transactionId;

    private UUID accountId;

    /** Positive debits the account, negative credits it; a transaction's postings sum to zero. */
    private BigDecimal amount;

    private Instant createdAt;

    public LedgerPosting(UUID transactionId, UUID accountId, BigDecimal amount) {
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.amount = amount;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
