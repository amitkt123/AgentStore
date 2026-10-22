package com.agentstore.platform.payouts;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;

import java.util.List;
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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Withdrawal w where w.id = :id")
    Optional<Withdrawal> findByIdForUpdate(@Param("id") UUID id);

    List<Withdrawal> findByOrgIdOrderByCreatedAtDescIdDesc(UUID orgId, Pageable page);

    /** REQUESTED withdrawals created before {@code before}, oldest first: the ones the submit job retries. */
    @Query("select w.id from Withdrawal w where w.status = com.agentstore.platform.payouts.WithdrawalStatus.REQUESTED "
            + "and w.createdAt < :before order by w.createdAt, w.id")
    List<UUID> requestedIdsBefore(@Param("before") Instant before, Pageable page);

    /** PROCESSING withdrawals submitted before {@code before}, oldest first: the ones the maintenance poll checks. */
    @Query("select w.id from Withdrawal w where w.status = com.agentstore.platform.payouts.WithdrawalStatus.PROCESSING "
            + "and w.submittedAt is not null and w.submittedAt < :before order by w.submittedAt, w.id")
    List<UUID> processingIdsSubmittedBefore(@Param("before") Instant before, Pageable page);

    /** Admin list: newest first, each filter optional. */
    @Query("select w from Withdrawal w where (:status is null or w.status = :status) "
            + "and (:orgId is null or w.orgId = :orgId) order by w.createdAt desc, w.id desc")
    List<Withdrawal> findForAdmin(@Param("status") WithdrawalStatus status, @Param("orgId") UUID orgId, Pageable page);
}
