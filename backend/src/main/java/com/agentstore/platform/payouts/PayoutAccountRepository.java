package com.agentstore.platform.payouts;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

import java.util.Optional;
import java.util.UUID;

public interface PayoutAccountRepository extends JpaRepository<PayoutAccount, UUID> {

    Optional<PayoutAccount> findByOrgIdAndReplacedAtIsNull(UUID orgId);

    Optional<PayoutAccount> findByProviderValidationId(String providerValidationId);

    long countByOrgIdAndCreatedAtGreaterThanEqual(UUID orgId, Instant since);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PayoutAccount a where a.id = :id")
    Optional<PayoutAccount> findByIdForUpdate(@Param("id") UUID id);

    /** PENDING accounts with a provider validation created before {@code before}, oldest first. */
    @Query("select a.id from PayoutAccount a where a.status = com.agentstore.platform.payouts.PayoutAccountStatus.PENDING "
            + "and a.providerValidationId is not null and a.createdAt < :before order by a.createdAt, a.id")
    List<UUID> pendingValidationIdsBefore(@Param("before") Instant before, Pageable page);

    /** Active (not replaced) accounts in {@code status}, newest first. */
    @Query("select a from PayoutAccount a where a.replacedAt is null and a.status = :status "
            + "order by a.createdAt desc, a.id desc")
    List<PayoutAccount> findActiveByStatus(@Param("status") PayoutAccountStatus status, Pageable page);
}
