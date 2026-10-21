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

    /** Balance of each existing platform (org-less) account, as {kind, balance} rows. */
    @Query("select a.kind, b.balance from LedgerAccount a, LedgerAccountBalance b "
            + "where b.accountId = a.id and a.orgId is null order by a.kind")
    java.util.List<Object[]> platformBalances();

    @Query("select coalesce(sum(b.balance), 0) from LedgerAccountBalance b")
    java.math.BigDecimal totalOfAllBalances();
}
