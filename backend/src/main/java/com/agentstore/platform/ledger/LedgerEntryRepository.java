package com.agentstore.platform.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {
    Optional<LedgerEntry> findByUsageEventId(UUID usageEventId);

    List<LedgerEntry> findByOrgId(UUID orgId);

    List<LedgerEntry> findByOrgIdOrderByCreatedAtDesc(UUID orgId);
}
