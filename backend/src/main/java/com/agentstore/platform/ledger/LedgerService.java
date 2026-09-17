package com.agentstore.platform.ledger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class LedgerService {

    private final UsageEventRepository usageEventRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final WalletRepository walletRepository;

    public LedgerService(UsageEventRepository usageEventRepository, LedgerEntryRepository ledgerEntryRepository,
                          WalletRepository walletRepository) {
        this.usageEventRepository = usageEventRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.walletRepository = walletRepository;
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

        Wallet wallet = walletRepository.findByOrgId(event.getOrgId())
                .orElseThrow(() -> new IllegalStateException("No wallet for org " + event.getOrgId()));
        wallet.setBalance(wallet.getBalance().subtract(event.getAmount()));
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);

        event.setProcessed(true);
        usageEventRepository.save(event);

        return entry;
    }
}
