package com.agentstore.platform.identity;

import com.agentstore.platform.ledger.BillingMode;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "org")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Org {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrgType type;

    public static final long DEFAULT_STORAGE_LIMIT_BYTES = 5L * 1024 * 1024 * 1024;

    @Column(name = "storage_limit_bytes", nullable = false)
    private long storageLimitBytes = DEFAULT_STORAGE_LIMIT_BYTES;

    /** Set by the developer verification workflow (WS7). Until that exists, tests and local development set it directly. */
    private Instant developerVerifiedAt;

    private Instant createdAt;

    /** Per-org platform fee override for the org's agents; null falls back to config. */
    private BigDecimal platformFeePercent;

    /** Null means the configured default billing mode. */
    @Enumerated(EnumType.STRING)
    private BillingMode billingMode;

    /** Postpaid credit limit; null means the configured default. */
    private BigDecimal creditLimit;

    /** Set by an admin to stop all money leaving the org (withdrawals and buyer refunds); null when not frozen. */
    private Instant moneyFrozenAt;

    @Column(length = 500)
    private String moneyFrozenReason;

    public Org(UUID id, String name, OrgType type) {
        this.id = id;
        this.name = name;
        this.type = type;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
