package com.agentstore.platform.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {
    Optional<LedgerAccount> findByOrgIdAndKind(UUID orgId, LedgerAccountKind kind);

    Optional<LedgerAccount> findByOrgIdIsNullAndKind(LedgerAccountKind kind);
}
