package com.agentstore.platform.payouts;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

import java.util.Optional;
import java.util.UUID;

public interface PayoutAccountRepository extends JpaRepository<PayoutAccount, UUID> {

    Optional<PayoutAccount> findByOrgIdAndReplacedAtIsNull(UUID orgId);

    Optional<PayoutAccount> findByProviderValidationId(String providerValidationId);

    long countByOrgIdAndCreatedAtGreaterThanEqual(UUID orgId, Instant since);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PayoutAccount a where a.id = :id")
    Optional<PayoutAccount> findByIdForUpdate(@Param("id") UUID id);
}
