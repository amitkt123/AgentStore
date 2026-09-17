package com.agentstore.platform.ledger;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerServiceTest extends AbstractIntegrationTest {

    @Autowired
    LedgerService ledgerService;
    @Autowired
    UsageEventRepository usageEventRepository;
    @Autowired
    LedgerEntryRepository ledgerEntryRepository;

    @Test
    void chargingAUsageEventCreatesExactlyOneLedgerEntryEvenIfCalledTwice() {
        UUID runId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UsageEvent event = usageEventRepository.save(
                new UsageEvent(null, runId, orgId, new BigDecimal("5"), new BigDecimal("2.00"), new BigDecimal("10.00")));

        LedgerEntry first = ledgerService.chargeUsageEvent(event.getId());
        LedgerEntry second = ledgerService.chargeUsageEvent(event.getId());

        assertThat(first.getId()).isEqualTo(second.getId());
        assertThat(ledgerEntryRepository.findByOrgId(orgId)).hasSize(1);
        assertThat(first.getAmount()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(first.getType()).isEqualTo(LedgerEntryType.CHARGE);
        assertThat(usageEventRepository.findById(event.getId()).orElseThrow().isProcessed()).isTrue();
    }
}
