package com.agentstore.platform.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LedgerPostingRepository extends JpaRepository<LedgerPosting, UUID> {
    @Query("select coalesce(sum(p.amount), 0) from LedgerPosting p where p.accountId = :accountId")
    BigDecimal sumByAccountId(@Param("accountId") UUID accountId);

    List<LedgerPosting> findByTransactionId(UUID transactionId);

    /** What the org was charged for runs since {@code from}: minus its RUN_CHARGE postings on its wallet and receivable accounts. */
    @Query("""
            select coalesce(-sum(p.amount), 0) from LedgerPosting p, LedgerTransaction t, LedgerAccount a
            where p.transactionId = t.id and p.accountId = a.id
              and t.type = com.agentstore.platform.ledger.LedgerTransactionType.RUN_CHARGE
              and t.orgId = :orgId and a.orgId = :orgId
              and a.kind in (com.agentstore.platform.ledger.LedgerAccountKind.BUYER_WALLET,
                             com.agentstore.platform.ledger.LedgerAccountKind.BUYER_RECEIVABLE)
              and t.createdAt >= :from""")
    BigDecimal runChargesSince(@Param("orgId") UUID orgId, @Param("from") Instant from);
}
