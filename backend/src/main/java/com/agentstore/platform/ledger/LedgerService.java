package com.agentstore.platform.ledger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class LedgerService {

    private final UsageEventRepository usageEventRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerService(UsageEventRepository usageEventRepository, LedgerEntryRepository ledgerEntryRepository) {
        this.usageEventRepository = usageEventRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public LedgerEntry chargeUsageEvent(UUID usageEventId) {
        var existing = ledgerEntryRepository.findByUsageEventId(usageEventId);
        if (existing.isPresent()) {
            return existing.get();
        }

        UsageEvent event = usageEventRepository.findById(usageEventId)
                .orElseThrow(() -> new IllegalArgumentException("UsageEvent not found: " + usageEventId));

        LedgerEntry entry = ledgerEntryRepository.save(new LedgerEntry(null, event.getId(), event.getRunId(),
                event.getOrgId(), event.getAmount(), LedgerEntryType.CHARGE));

        event.setProcessed(true);
        usageEventRepository.save(event);

        return entry;
    }
}
