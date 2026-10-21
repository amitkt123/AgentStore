package com.agentstore.platform.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {
    Optional<LedgerAccount> findByOrgIdAndKind(UUID orgId, LedgerAccountKind kind);

    Optional<LedgerAccount> findByOrgIdIsNullAndKind(LedgerAccountKind kind);

    /** Creates the org's account unless it exists; a concurrent creator's row wins without an error. */
    @Modifying
    @Query(value = "insert into ledger_account (id, org_id, kind) values (gen_random_uuid(), :orgId, :kind) "
            + "on conflict do nothing", nativeQuery = true)
    int insertOrgAccountIfMissing(@Param("orgId") UUID orgId, @Param("kind") String kind);

    /** Creates the platform account unless it exists; a concurrent creator's row wins without an error. */
    @Modifying
    @Query(value = "insert into ledger_account (id, org_id, kind) values (gen_random_uuid(), null, :kind) "
            + "on conflict do nothing", nativeQuery = true)
    int insertPlatformAccountIfMissing(@Param("kind") String kind);

    @Modifying
    @Query(value = "insert into ledger_account_balance (account_id) values (:accountId) on conflict do nothing",
            nativeQuery = true)
    int insertBalanceIfMissing(@Param("accountId") UUID accountId);
}
