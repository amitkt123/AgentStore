package com.agentstore.platform.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {
    /**
     * Locks the org's wallet row. A wallet already managed in the caller's persistence context is returned as-is,
     * without the locked row's values, so callers that read balance or reserved refresh it after locking.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional
    Optional<Wallet> findByOrgId(UUID orgId);

    /** The org's wallet without locking its row, for read-only views. */
    @Query("select w from Wallet w where w.orgId = :orgId")
    Optional<Wallet> findReadOnlyByOrgId(@Param("orgId") UUID orgId);

    /** Creates an empty wallet (balance 0, reserved 0) unless the org has one; a concurrent creator wins quietly. */
    @Modifying
    @Transactional
    @Query(value = "insert into wallet (id, org_id, balance, reserved) values (gen_random_uuid(), :orgId, 0, 0) "
            + "on conflict (org_id) do nothing", nativeQuery = true)
    int insertEmptyIfMissing(@Param("orgId") UUID orgId);

    /** The org's balance without locking its row, for read-only views. */
    @Query("select w.balance from Wallet w where w.orgId = :orgId")
    Optional<java.math.BigDecimal> balanceOfOrg(@Param("orgId") UUID orgId);
}
