package com.agentstore.platform.ledger;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.execution.Connection;
import com.agentstore.platform.execution.ConnectionRepository;
import com.agentstore.platform.execution.ConnectionStatus;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunLifecycleService;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.execution.RunStepKind;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.llm.LlmCall;
import com.agentstore.platform.llm.LlmCallRepository;
import com.agentstore.platform.llm.LlmCallStatus;
import com.agentstore.platform.llm.LlmFixtures;
import com.agentstore.platform.llm.LlmSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Charges for two buyers on one developer's agent, admin adjustments, run finishes that post LLM cost, and refused
 * mode switches, all at once from several threads. Every path takes the wallet row before ledger balance rows, so
 * none of them may deadlock, and the ledger's invariants must hold afterwards.
 */
class LedgerConcurrencyInvariantTest extends AbstractIntegrationTest {

    private static final int CHARGES_PER_BUYER = 25;
    private static final int ADJUSTMENTS = 20;
    private static final int FINISHES_PER_BUYER = 8;
    private static final int MODE_ATTEMPTS = 15;
    private static final BigDecimal FUNDING = new BigDecimal("1000.00");
    private static final BigDecimal CHARGE = new BigDecimal("3.00");
    private static final BigDecimal ADJUSTMENT = new BigDecimal("10.00");
    private static final BigDecimal HOLD = new BigDecimal("5.00");
    private static final BigDecimal LLM_COST = new BigDecimal("1.25");

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    LlmFixtures llmFixtures;
    @Autowired
    LedgerService ledgerService;
    @Autowired
    LedgerAccountRepository accounts;
    @Autowired
    UsageEventRepository usageEvents;
    @Autowired
    WalletRepository walletRepository;
    @Autowired
    WalletReservationService walletReservationService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    ConnectionRepository connectionRepository;
    @Autowired
    RunRepository runRepository;
    @Autowired
    RunLifecycleService lifecycle;
    @Autowired
    LlmCallRepository llmCalls;
    @Autowired
    BillingModeService billingModeService;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void concurrentMoneyOperationsKeepTheLedgerConsistent() throws Exception {
        llmFixtures.ensureModels();
        ExecutionFixtures.Scenario first = fixtures.liveTierA(new BigDecimal("100000.00"), new BigDecimal("0.00"));
        AgentVersion version = first.version();
        UUID developerOrgId = version.getAgent().getDeveloperOrg().getId();
        Org buyerB = orgRepository.save(new Org(null, "Buyer-" + UUID.randomUUID(), OrgType.BUYER));
        Wallet walletB = new Wallet(null, buyerB.getId(), BigDecimal.ZERO, null);
        walletB.setUpdatedAt(Instant.now());
        walletRepository.save(walletB);
        Connection connectionB = new Connection(null, buyerB, version.getAgent(), List.of("bank_statement"),
                new BigDecimal("100000.00"), ConnectionStatus.ACTIVE);
        connectionB.setAgentVersion(version);
        ExecutionFixtures.Scenario second = new ExecutionFixtures.Scenario(buyerB, version,
                connectionRepository.save(connectionB));
        List<ExecutionFixtures.Scenario> buyers = List.of(first, second);

        // Fund both wallets through the ledger, then prepare every run and usage event before the race starts.
        for (ExecutionFixtures.Scenario s : buyers) {
            assertThat(adjust(s.buyer().getId(), FUNDING)).isEqualTo(HttpStatus.OK);
        }
        List<List<UUID>> chargeEvents = new ArrayList<>();
        List<List<UUID>> finishingRuns = new ArrayList<>();
        for (ExecutionFixtures.Scenario s : buyers) {
            Run chargedRun = run(s);
            List<UUID> events = new ArrayList<>();
            for (int i = 0; i < CHARGES_PER_BUYER; i++) {
                events.add(usageEvents.save(new UsageEvent(null, chargedRun.getId(), s.buyer().getId(),
                        BigDecimal.ONE, CHARGE, CHARGE)).getId());
            }
            chargeEvents.add(events);
            List<UUID> runs = new ArrayList<>();
            for (int i = 0; i < FINISHES_PER_BUYER; i++) {
                Run run = run(s);
                walletReservationService.reserve(s.buyer().getId(), HOLD);
                run.setReservedAmount(HOLD);
                runRepository.save(run);
                LlmCall call = new LlmCall(UUID.randomUUID(), run.getId(), LlmFixtures.CLAUDE, LlmSource.PLATFORM,
                        LlmCallStatus.OK, "h-" + UUID.randomUUID());
                call.setCost(LLM_COST);
                llmCalls.save(call);
                runs.add(run.getId());
            }
            finishingRuns.add(runs);
        }

        List<Callable<Void>> work = new ArrayList<>();
        for (List<UUID> events : chargeEvents) {
            work.add(() -> {
                for (UUID eventId : events) {
                    ledgerService.chargeUsageEvent(eventId);
                }
                return null;
            });
        }
        for (List<UUID> runs : finishingRuns) {
            work.add(() -> {
                for (UUID runId : runs) {
                    lifecycle.recordEvent(runId, RunStepKind.COMPLETE, Map.of());
                }
                return null;
            });
        }
        work.add(() -> {
            for (int i = 0; i < ADJUSTMENTS; i++) {
                HttpStatus status = adjust(buyers.get(i % 2).buyer().getId(), ADJUSTMENT);
                assertThat(status).as("adjustment %d", i).isEqualTo(HttpStatus.OK);
            }
            return null;
        });
        work.add(() -> {
            int refused = 0;
            for (int i = 0; i < MODE_ATTEMPTS; i++) {
                try {
                    // Refused: the wallet holds money. The check still takes the wallet lock each time.
                    billingModeService.setMode(buyers.get(i % 2).buyer().getId(), BillingMode.POSTPAID);
                } catch (ResponseStatusException e) {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    refused++;
                }
            }
            assertThat(refused).isEqualTo(MODE_ATTEMPTS);
            return null;
        });

        ExecutorService pool = Executors.newFixedThreadPool(work.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> task : work) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            // Bounded: a deadlock (or a lock wait that never ends) fails here instead of hanging the build.
            for (Future<Void> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // Every transaction balances.
        assertThat(jdbc.queryForList(
                "select transaction_id from ledger_posting group by transaction_id having sum(amount) <> 0"))
                .as("unbalanced transactions").isEmpty();

        // Every balance this test touched equals the sum of its postings.
        List<UUID> touched = jdbc.queryForList("select id from ledger_account where org_id in (?, ?, ?) "
                        + "or org_id is null", UUID.class, first.buyer().getId(), buyerB.getId(), developerOrgId);
        List<UUID> drifted = jdbc.queryForList("select account_id from ledger_account_balance b where b.balance <> "
                + "(select coalesce(sum(p.amount), 0) from ledger_posting p where p.account_id = b.account_id)", UUID.class);
        assertThat(drifted).as("balances that differ from their postings").doesNotContainAnyElementsOf(touched);

        // Each prepaid buyer's wallet agrees with its BUYER_WALLET account and with what happened to it.
        BigDecimal expected = FUNDING.add(ADJUSTMENT.multiply(BigDecimal.valueOf(ADJUSTMENTS / 2)))
                .subtract(CHARGE.multiply(BigDecimal.valueOf(CHARGES_PER_BUYER)));
        for (ExecutionFixtures.Scenario s : buyers) {
            UUID orgId = s.buyer().getId();
            assertThat(billingModeService.modeFor(orgId)).isEqualTo(BillingMode.PREPAID);
            Wallet wallet = walletRepository.findReadOnlyByOrgId(orgId).orElseThrow();
            BigDecimal ledgerWallet = ledgerService.balanceOf(
                    accounts.findByOrgIdAndKind(orgId, LedgerAccountKind.BUYER_WALLET).orElseThrow().getId());
            assertThat(wallet.getBalance()).as("wallet of %s", orgId).isEqualByComparingTo(ledgerWallet);
            assertThat(wallet.getBalance()).as("wallet of %s", orgId).isEqualByComparingTo(expected);
            assertThat(wallet.getReserved()).as("holds released for %s", orgId).isEqualByComparingTo("0");
        }

        // Every finished run's LLM cost was deducted once.
        long llmPosts = jdbc.queryForObject("select count(*) from ledger_transaction where type = 'LLM_COST' "
                + "and org_id = ?", Long.class, developerOrgId);
        assertThat(llmPosts).isEqualTo(2L * FINISHES_PER_BUYER);
    }

    private Run run(ExecutionFixtures.Scenario scenario) {
        return fixtures.run(scenario, RunStatus.RUNNING, "sbx-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());
    }

    private HttpStatus adjust(UUID orgId, BigDecimal amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), UUID.randomUUID(), UserRole.ADMIN));
        var response = rest.exchange("http://localhost:" + port + "/api/v1/admin/orgs/" + orgId + "/wallet/adjustments",
                HttpMethod.POST, new HttpEntity<>(Map.of("requestId", "concurrent-" + UUID.randomUUID(),
                        "amount", amount, "note", "concurrency test"), headers), String.class);
        return HttpStatus.valueOf(response.getStatusCode().value());
    }
}
