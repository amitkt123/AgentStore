package com.agentstore.platform.payouts;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "withdrawal")
@Getter
@Setter
@NoArgsConstructor
public class Withdrawal {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "payout_account_id")
    private UUID payoutAccountId;

    @Column(name = "request_id")
    private UUID requestId;

    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private PayoutMode mode;

    @Enumerated(EnumType.STRING)
    private WithdrawalStatus status;

    @Column(name = "provider_payout_id")
    private String providerPayoutId;

    private String utr;
    private BigDecimal fees;

    @Column(name = "requested_by_user_id")
    private UUID requestedByUserId;

    private String failureReason;
    private Instant createdAt;
    private Instant submittedAt;
    private Instant processedAt;
    private Instant settledAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
