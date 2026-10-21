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

    /** Accounts whose stored balance differs from the sum of their postings. */
    @Query(value = "select b.account_id from ledger_account_balance b "
            + "left join (select account_id, sum(amount) as total from ledger_posting group by account_id) p "
            + "on p.account_id = b.account_id where b.balance <> coalesce(p.total, 0)", nativeQuery = true)
    java.util.List<UUID> accountsNotMatchingPostings();

    /**
     * Orgs whose {@code wallet.balance} differs from their BUYER_WALLET balance. A wallet without a BUYER_WALLET
     * account (funded before WS10a) is skipped: its balance becomes the account's opening balance on first use.
     */
    @Query(value = "select w.org_id from wallet w "
            + "join ledger_account a on a.org_id = w.org_id and a.kind = 'BUYER_WALLET' "
            + "join ledger_account_balance b on b.account_id = a.id "
            + "where w.balance <> b.balance", nativeQuery = true)
    java.util.List<UUID> walletsNotMatchingLedger();
}
