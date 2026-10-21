package com.agentstore.platform.ledger;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.Agent;
import com.agentstore.platform.catalog.AgentRepository;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunLifecycleService;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.execution.RunStepKind;
import com.agentstore.platform.execution.RunTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerChargeSplitTest extends AbstractIntegrationTest {

    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    LedgerService ledgerService;
    @Autowired
    LedgerAccounts ledgerAccounts;
    @Autowired
    LedgerAccountRepository accounts;
    @Autowired
    LedgerTransactionRepository transactions;
    @Autowired
    LedgerPostingRepository postings;
    @Autowired
    UsageEventRepository usageEvents;
    @Autowired
    WalletRepository walletRepository;
    @Autowired
    AgentRepository agentRepository;
    @Autowired
    RunRepository runRepository;
    @Autowired
    RunLifecycleService lifecycle;
    @Autowired
    JdbcTemplate jdbc;

    record Setup(ExecutionFixtures.Scenario scenario, Run run) {
        UUID buyerOrgId() {
            return scenario.buyer().getId();
        }

        UUID developerOrgId() {
            return scenario.version().getAgent().getDeveloperOrg().getId();
        }
    }

    Setup setup(String walletBalance) {
        ExecutionFixtures.Scenario scenario = fixtures.liveTierA(new BigDecimal("100000.00"), new BigDecimal(walletBalance));
        Run run = fixtures.run(scenario, RunStatus.RUNNING, "sbx-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());
        return new Setup(scenario, run);
    }

    UUID usageEvent(Setup s, String amount) {
        return usageEvents.save(new UsageEvent(null, s.run().getId(), s.buyerOrgId(), BigDecimal.ONE,
                new BigDecimal(amount), new BigDecimal(amount))).getId();
    }

    BigDecimal orgBalance(UUID orgId, LedgerAccountKind kind) {
        return accounts.findByOrgIdAndKind(orgId, kind)
                .map(a -> ledgerService.balanceOf(a.getId()))
                .orElse(BigDecimal.ZERO);
    }

    BigDecimal platformRevenue() {
        return ledgerService.balanceOf(ledgerAccounts.platformAccount(LedgerAccountKind.PLATFORM_REVENUE));
    }

    BigDecimal walletBalance(UUID orgId) {
        return walletRepository.findByOrgId(orgId).orElseThrow().getBalance();
    }

    @Test
    void chargeSplitsIntoDeveloperAndPlatform() {
        Setup s = setup("1000.00");
        BigDecimal revenueBefore = platformRevenue();
        UUID eventId = usageEvent(s, "100.00");

        ledgerService.chargeUsageEvent(eventId);

        assertThat(orgBalance(s.buyerOrgId(), LedgerAccountKind.BUYER_WALLET)).isEqualByComparingTo("900.00");
        assertThat(orgBalance(s.developerOrgId(), LedgerAccountKind.DEVELOPER_PAYABLE)).isEqualByComparingTo("80.00");
        assertThat(platformRevenue().subtract(revenueBefore)).isEqualByComparingTo("20.00");
        assertThat(walletBalance(s.buyerOrgId())).isEqualByComparingTo("900.00");

        LedgerTransaction charge = transactions.findByIdempotencyKey("usage:" + eventId).orElseThrow();
        assertThat(charge.getType()).isEqualTo(LedgerTransactionType.RUN_CHARGE);
        assertThat(charge.getRunId()).isEqualTo(s.run().getId());
        assertThat(charge.getOrgId()).isEqualTo(s.buyerOrgId());
        assertThat(charge.getReference()).containsEntry("agentId", s.scenario().version().getAgent().getId().toString())
                .containsEntry("agentVersionId", s.scenario().version().getId().toString())
                .containsKey("feePercent");
        assertThat(new BigDecimal(String.valueOf(charge.getReference().get("feePercent")))).isEqualByComparingTo("20");
        // No tax lines while TDS and GST TCS are disabled.
        assertThat(postings.findByTransactionId(charge.getId())).hasSize(3);
    }

    @Test
    void chargeIsIdempotent() {
        Setup s = setup("1000.00");
        UUID eventId = usageEvent(s, "100.00");

        LedgerEntry first = ledgerService.chargeUsageEvent(eventId);
        BigDecimal revenueAfterFirst = platformRevenue();
        LedgerEntry second = ledgerService.chargeUsageEvent(eventId);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(orgBalance(s.buyerOrgId(), LedgerAccountKind.BUYER_WALLET)).isEqualByComparingTo("900.00");
        assertThat(orgBalance(s.developerOrgId(), LedgerAccountKind.DEVELOPER_PAYABLE)).isEqualByComparingTo("80.00");
        assertThat(platformRevenue()).isEqualByComparingTo(revenueAfterFirst);
        assertThat(walletBalance(s.buyerOrgId())).isEqualByComparingTo("900.00");
        assertThat(jdbc.queryForObject("select count(*) from ledger_transaction where run_id = ? and type = 'RUN_CHARGE'",
                Long.class, s.run().getId())).isEqualTo(1L);
    }

    @Test
    void agentFeeOverrideApplies() {
        Setup s = setup("1000.00");
        Agent agent = agentRepository.findById(s.scenario().version().getAgent().getId()).orElseThrow();
        agent.setPlatformFeePercent(new BigDecimal("10"));
        agentRepository.save(agent);
        BigDecimal revenueBefore = platformRevenue();

        ledgerService.chargeUsageEvent(usageEvent(s, "100.00"));

        assertThat(orgBalance(s.developerOrgId(), LedgerAccountKind.DEVELOPER_PAYABLE)).isEqualByComparingTo("90.00");
        assertThat(platformRevenue().subtract(revenueBefore)).isEqualByComparingTo("10.00");
    }

    @Test
    void postingsSumToZeroForAwkwardAmounts() {
        Setup s = setup("1000.00");
        BigDecimal revenueBefore = platformRevenue();
        BigDecimal total = BigDecimal.ZERO;

        for (String amount : List.of("0.01", "33.33", "99.99")) {
            UUID eventId = usageEvent(s, amount);
            ledgerService.chargeUsageEvent(eventId);
            total = total.add(new BigDecimal(amount));

            LedgerTransaction charge = transactions.findByIdempotencyKey("usage:" + eventId).orElseThrow();
            BigDecimal sum = postings.findByTransactionId(charge.getId()).stream()
                    .map(LedgerPosting::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum).as("postings of a %s charge", amount).isEqualByComparingTo("0");
        }

        BigDecimal developer = orgBalance(s.developerOrgId(), LedgerAccountKind.DEVELOPER_PAYABLE);
        BigDecimal platform = platformRevenue().subtract(revenueBefore);
        assertThat(developer.add(platform)).isEqualByComparingTo(total);
        assertThat(orgBalance(s.buyerOrgId(), LedgerAccountKind.BUYER_WALLET))
                .isEqualByComparingTo(new BigDecimal("1000.00").subtract(total));
    }

    @Test
    void concurrentChargesOnOneWalletStayConsistent() throws Exception {
        Setup s = setup("1000.00");
        List<UUID> events = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            events.add(usageEvent(s, "1.50"));
        }

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<LedgerEntry>> futures = new ArrayList<>();
            for (UUID eventId : events) {
                futures.add(pool.submit(() -> ledgerService.chargeUsageEvent(eventId)));
            }
            for (Future<LedgerEntry> f : futures) {
                f.get();   // rethrows any failure
            }
        } finally {
            pool.shutdownNow();
        }

        UUID buyerWallet = accounts.findByOrgIdAndKind(s.buyerOrgId(), LedgerAccountKind.BUYER_WALLET).orElseThrow().getId();
        BigDecimal ledgerBalance = ledgerService.balanceOf(buyerWallet);
        BigDecimal opening = new BigDecimal("1000.00");
        assertThat(ledgerBalance).isEqualByComparingTo(postings.sumByAccountId(buyerWallet));
        assertThat(ledgerBalance).isEqualByComparingTo(walletBalance(s.buyerOrgId()));
        assertThat(opening.subtract(ledgerBalance)).isEqualByComparingTo("30.00");
        assertThat(orgBalance(s.developerOrgId(), LedgerAccountKind.DEVELOPER_PAYABLE)).isEqualByComparingTo("24.00");
    }

    @Test
    void sandboxRunPostsNothing() {
        Setup s = setup("1000.00");
        Run sandbox = new Run(null, null, s.scenario().version(), RunStatus.RUNNING, new BigDecimal("100.00"),
                BigDecimal.ZERO, "sbx-" + UUID.randomUUID(), RunTokenService.hash("tok-" + UUID.randomUUID()));
        sandbox.setMode(RunMode.SANDBOX);
        sandbox.setInitiatedByOrgId(s.developerOrgId());
        sandbox = runRepository.save(sandbox);

        lifecycle.recordEvent(sandbox.getId(), RunStepKind.COST, Map.of("quantity", 1));

        assertThat(runRepository.findById(sandbox.getId()).orElseThrow().getSimulatedCharged()).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from ledger_transaction where run_id = ?",
                Long.class, sandbox.getId())).isZero();
    }
}
