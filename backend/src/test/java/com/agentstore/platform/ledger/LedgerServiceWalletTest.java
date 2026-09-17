package com.agentstore.platform.ledger;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerServiceWalletTest extends AbstractIntegrationTest {

    @Autowired
    LedgerService ledgerService;
    @Autowired
    UsageEventRepository usageEventRepository;
    @Autowired
    WalletRepository walletRepository;

    @Test
    @Transactional
    void chargingDebitsTheWalletExactlyOnce() {
        UUID orgId = UUID.randomUUID();
        Wallet wallet = new Wallet(null, orgId, new BigDecimal("1000.00"), null);
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);

        UsageEvent event = usageEventRepository.save(new UsageEvent(null, UUID.randomUUID(), orgId,
                new BigDecimal("5"), new BigDecimal("2.00"), new BigDecimal("10.00")));

        ledgerService.chargeUsageEvent(event.getId());
        ledgerService.chargeUsageEvent(event.getId());

        Wallet updated = walletRepository.findByOrgId(orgId).orElseThrow();
        assertThat(updated.getBalance()).isEqualByComparingTo(new BigDecimal("990.00"));
    }
}
