package com.agentstore.platform.ledger;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.RunStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerServiceTest extends AbstractIntegrationTest {

    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    LedgerService ledgerService;
    @Autowired
    UsageEventRepository usageEventRepository;
    @Autowired
    LedgerEntryRepository ledgerEntryRepository;

    @Test
    void chargingAUsageEventCreatesExactlyOneLedgerEntryEvenIfCalledTwice() {
        // A charge needs a real run: it pays the run's agent developer.
        ExecutionFixtures.Scenario scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        UUID runId = fixtures.run(scenario, RunStatus.RUNNING, "sbx-" + UUID.randomUUID(), "tok-" + UUID.randomUUID()).getId();
        UUID orgId = scenario.buyer().getId();

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
