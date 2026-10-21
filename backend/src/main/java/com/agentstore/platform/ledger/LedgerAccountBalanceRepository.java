package com.agentstore.platform.ledger;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface LedgerAccountBalanceRepository extends JpaRepository<LedgerAccountBalance, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from LedgerAccountBalance b where b.accountId = :accountId")
    Optional<LedgerAccountBalance> findForUpdate(@Param("accountId") UUID accountId);
}
