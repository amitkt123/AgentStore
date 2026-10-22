package com.agentstore.platform.payouts;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;

import java.util.Optional;
import java.util.UUID;

public interface WithdrawalRepository extends JpaRepository<Withdrawal, UUID> {

    Optional<Withdrawal> findByOrgIdAndRequestId(UUID orgId, UUID requestId);

    Optional<Withdrawal> findByProviderPayoutId(String providerPayoutId);

    /** Sum of the org's withdrawals created since {@code since} that still count against the daily limit. */
    @Query("select coalesce(sum(w.amount), 0) from Withdrawal w where w.orgId = :orgId and w.createdAt >= :since "
            + "and w.status in (com.agentstore.platform.payouts.WithdrawalStatus.REQUESTED, "
            + "com.agentstore.platform.payouts.WithdrawalStatus.PROCESSING, "
            + "com.agentstore.platform.payouts.WithdrawalStatus.PROCESSED)")
    BigDecimal sumCountedSince(@Param("orgId") UUID orgId, @Param("since") Instant since);
}
