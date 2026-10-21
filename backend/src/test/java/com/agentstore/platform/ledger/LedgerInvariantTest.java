package com.agentstore.platform.ledger;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.Agent;
import com.agentstore.platform.catalog.AgentRepository;
import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.execution.Connection;
import com.agentstore.platform.execution.ConnectionRepository;
import com.agentstore.platform.execution.ConnectionStatus;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A seeded random mix of ledger operations, driven through the real services, after which the ledger's invariants
 * must hold: every transaction balances, every cached balance equals its postings, the charge split accounts for every
 * rupee charged, and each prepaid wallet agrees with its BUYER_WALLET account.
 */
class LedgerInvariantTest extends AbstractIntegrationTest {

    private static final int OPERATIONS = 200;
    private static final int POSTPAID_SWITCH_AT = 100;
    /** Platform accounts that, with the developer payables, must together hold exactly what was charged. */
    private static final List<LedgerAccountKind> SPLIT_PLATFORM_KINDS = List.of(LedgerAccountKind.PLATFORM_REVENUE,
            LedgerAccountKind.TAX_TDS_PAYABLE, LedgerAccountKind.TAX_TCS_PAYABLE, LedgerAccountKind.PLATFORM_LLM_COST);

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
    LedgerAccounts ledgerAccounts;
    @Autowired
    LedgerAccountRepository accounts;
    @Autowired
    LedgerPostingRepository postings;
    @Autowired
    UsageEventRepository usageEvents;
    @Autowired
    WalletRepository walletRepository;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    AgentRepository agentRepository;
    @Autowired
    ConnectionRepository connectionRepository;
    @Autowired
    RunRepository runRepository;
    @Autowired
    LlmCallRepository llmCalls;
    @Autowired
    RunLlmCostService runLlmCostService;
    @Autowired
    BillingModeService billingModeService;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;

    /** A run not yet finished: it can take more charges and, once, its LLM cost. */
    record OpenRun(Run run, UUID buyerOrgId) {}

    @Test
    void ledgerInvariantsHoldAfterRandomOperations() {
        Random random = new Random(42);
        llmFixtures.ensureModels();
        Map<LedgerAccountKind, BigDecimal> platformBefore = platformBalances();

        // Two developers. Dev 1's agent starts on the default fee and gets an agent override part-way; dev 2 has an
        // org-level override throughout.
        AgentVersion version1 = fixtures.liveTierA(new BigDecimal("100000.00"), BigDecimal.ZERO).version();
        AgentVersion version2 = fixtures.liveTierA(new BigDecimal("100000.00"), BigDecimal.ZERO).version();
        Org dev2 = orgRepository.findById(version2.getAgent().getDeveloperOrg().getId()).orElseThrow();
        dev2.setPlatformFeePercent(new BigDecimal("15"));
        orgRepository.save(dev2);
        List<AgentVersion> versions = List.of(version1, version2);
        List<UUID> developers = List.of(version1.getAgent().getDeveloperOrg().getId(), dev2.getId());

        // Three prepaid buyers, each connected to both agents. The last one is emptied and switched to postpaid.
        List<Org> buyers = List.of(buyer("1000.00"), buyer("2000.00"), buyer("300.00"));
        Org postpaidBuyer = buyers.get(2);
        Map<String, Connection> connections = new HashMap<>();
        for (Org buyer : buyers) {
            for (AgentVersion version : versions) {
                connections.put(buyer.getId() + "/" + version.getId(), connection(buyer, version));
            }
        }

        List<OpenRun> openRuns = new ArrayList<>();
        BigDecimal totalCharged = BigDecimal.ZERO;
        BigDecimal totalLlmCost = BigDecimal.ZERO;
        int charges = 0, llmCosts = 0, adjustments = 0;

        for (int op = 0; op < OPERATIONS; op++) {
            if (op == POSTPAID_SWITCH_AT) {
                emptyWallet(postpaidBuyer.getId(), op);
                billingModeService.setMode(postpaidBuyer.getId(), BillingMode.POSTPAID);
            }
            if (op == OPERATIONS / 2 + 20) {
                Agent agent1 = agentRepository.findById(version1.getAgent().getId()).orElseThrow();
                agent1.setPlatformFeePercent(new BigDecimal("12.5"));
                agentRepository.save(agent1);
            }

            int kind = random.nextInt(10);
            if (kind < 6) {
                // Charge: reuse an open run for the pair half the time, else start a new one.
                Org buyer = buyers.get(random.nextInt(buyers.size()));
                AgentVersion version = versions.get(random.nextInt(versions.size()));
                Connection connection = connections.get(buyer.getId() + "/" + version.getId());
                OpenRun target = openRuns.stream()
                        .filter(r -> r.buyerOrgId().equals(buyer.getId())
                                && r.run().getAgentVersion().getId().equals(version.getId()))
                        .findFirst()
                        .filter(r -> random.nextBoolean())
                        .orElse(null);
                if (target == null) {
                    target = new OpenRun(newRun(buyer, version, connection), buyer.getId());
                    openRuns.add(target);
                }
                BigDecimal amount = cents(random, 1, 5000);   // 0.01 .. 50.00
                if (isPrepaid(buyer.getId()) && available(buyer.getId()).compareTo(amount) < 0) {
                    // A prepaid run would not have been allowed to spend past the wallet; top it up first.
                    adjust(buyer.getId(), amount.add(new BigDecimal("100.00")), op);
                    adjustments++;
                }
                UUID eventId = usageEvents.save(new UsageEvent(null, target.run().getId(), buyer.getId(),
                        BigDecimal.ONE, amount, amount)).getId();
                ledgerService.chargeUsageEvent(eventId);
                if (random.nextInt(5) == 0) {
                    ledgerService.chargeUsageEvent(eventId);   // a redelivered event must not charge twice
                }
                totalCharged = totalCharged.add(amount);
                charges++;
            } else if (kind < 8) {
                // LLM cost: finish an open run with 1-3 platform-paid calls (sub-paisa costs round on posting).
                if (openRuns.isEmpty()) {
                    continue;
                }
                OpenRun finished = openRuns.remove(random.nextInt(openRuns.size()));
                BigDecimal cost = BigDecimal.ZERO;
                int calls = 1 + random.nextInt(3);
                for (int c = 0; c < calls; c++) {
                    BigDecimal callCost = BigDecimal.valueOf(random.nextInt(5000) + 1, 3);   // 0.001 .. 5.000
                    llmCall(finished.run(), LlmSource.PLATFORM, callCost);
                    cost = cost.add(callCost);
                }
                llmCall(finished.run(), LlmSource.BYOK_PROXY, new BigDecimal("9.999"));   // costs the platform nothing
                UUID runId = finished.run().getId();
                tx.executeWithoutResult(s -> runLlmCostService.post(runRepository.findById(runId).orElseThrow()));
                if (random.nextInt(4) == 0) {
                    tx.executeWithoutResult(s -> runLlmCostService.post(runRepository.findById(runId).orElseThrow()));
                }
                totalLlmCost = totalLlmCost.add(cost.setScale(2, RoundingMode.HALF_UP));
                llmCosts++;
            } else {
                // Admin adjustment on a prepaid wallet: a credit, or a debit within what is available.
                Org buyer = buyers.get(random.nextInt(buyers.size()));
                if (!isPrepaid(buyer.getId())) {
                    continue;
                }
                BigDecimal amount = cents(random, 1, 20000);   // 0.01 .. 200.00
                if (random.nextBoolean() && available(buyer.getId()).compareTo(amount) >= 0) {
                    amount = amount.negate();
                }
                adjust(buyer.getId(), amount, op);
                adjustments++;
            }
        }

        assertThat(billingModeService.modeFor(postpaidBuyer.getId())).isEqualTo(BillingMode.POSTPAID);
        assertThat(charges).as("charges").isGreaterThan(50);
        assertThat(llmCosts).as("LLM costs").isGreaterThan(10);
        assertThat(adjustments).as("adjustments").isGreaterThan(10);
        assertThat(totalLlmCost).isPositive();

        // (a) Every transaction's postings sum to zero.
        assertThat(jdbc.queryForList(
                "select transaction_id from ledger_posting group by transaction_id having sum(amount) <> 0"))
                .as("unbalanced transactions").isEmpty();

        // (b) Every cached balance equals the sum of its account's postings.
        List<Map<String, Object>> balances = jdbc.queryForList("select account_id, balance from ledger_account_balance");
        assertThat(balances).isNotEmpty();
        for (Map<String, Object> row : balances) {
            UUID accountId = (UUID) row.get("account_id");
            assertThat((BigDecimal) row.get("balance")).as("balance of account %s", accountId)
                    .isEqualByComparingTo(postings.sumByAccountId(accountId));
        }

        // (c) The charge split accounts for every charge. LLM cost moves money from the payables to
        // PLATFORM_LLM_COST, so it cancels within this sum.
        BigDecimal split = BigDecimal.ZERO;
        for (UUID developer : developers) {
            split = split.add(orgBalance(developer, LedgerAccountKind.DEVELOPER_PAYABLE));
        }
        Map<LedgerAccountKind, BigDecimal> platformAfter = platformBalances();
        for (LedgerAccountKind kind : SPLIT_PLATFORM_KINDS) {
            split = split.add(platformAfter.get(kind).subtract(platformBefore.get(kind)));
        }
        assertThat(split).as("payables + revenue + taxes + LLM cost").isEqualByComparingTo(totalCharged);
        assertThat(platformAfter.get(LedgerAccountKind.PLATFORM_LLM_COST)
                .subtract(platformBefore.get(LedgerAccountKind.PLATFORM_LLM_COST)))
                .as("LLM cost deducted").isEqualByComparingTo(totalLlmCost);

        // (d) Each prepaid wallet agrees with its BUYER_WALLET account.
        for (Org buyer : buyers) {
            if (!isPrepaid(buyer.getId())) {
                continue;
            }
            assertThat(wallet(buyer.getId()).getBalance()).as("wallet of buyer %s", buyer.getName())
                    .isEqualByComparingTo(orgBalance(buyer.getId(), LedgerAccountKind.BUYER_WALLET));
        }
        // The postpaid buyer's wallet stayed empty after the switch, and what it owes is on its receivable.
        assertThat(wallet(postpaidBuyer.getId()).getBalance()).isEqualByComparingTo("0");
        assertThat(orgBalance(postpaidBuyer.getId(), LedgerAccountKind.BUYER_WALLET)).isEqualByComparingTo("0");
        assertThat(billingModeService.owed(postpaidBuyer.getId())).isPositive();
    }

    private Org buyer(String walletBalance) {
        Org org = orgRepository.save(new Org(null, "Buyer-" + UUID.randomUUID(), OrgType.BUYER));
        Wallet wallet = new Wallet(null, org.getId(), new BigDecimal(walletBalance), null);
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);
        return org;
    }

    private Connection connection(Org buyer, AgentVersion version) {
        Connection connection = new Connection(null, buyer, version.getAgent(), List.of("bank_statement"),
                new BigDecimal("100000.00"), ConnectionStatus.ACTIVE);
        connection.setAgentVersion(version);
        return connectionRepository.save(connection);
    }

    private Run newRun(Org buyer, AgentVersion version, Connection connection) {
        return fixtures.run(new ExecutionFixtures.Scenario(buyer, version, connection), RunStatus.RUNNING,
                "sbx-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());
    }

    private void llmCall(Run run, LlmSource source, BigDecimal cost) {
        LlmCall call = new LlmCall(UUID.randomUUID(), run.getId(), LlmFixtures.CLAUDE, source, LlmCallStatus.OK,
                "h-" + UUID.randomUUID());
        call.setCost(cost);
        llmCalls.save(call);
    }

    /** Posts an ADJUSTMENT through the admin API, as the Money console does. */
    private void adjust(UUID orgId, BigDecimal amount, int op) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), UUID.randomUUID(), UserRole.ADMIN));
        var response = rest.exchange("http://localhost:" + port + "/api/v1/admin/orgs/" + orgId + "/wallet/adjustments",
                HttpMethod.POST, new HttpEntity<>(Map.of("requestId", "invariant-" + UUID.randomUUID(),
                        "amount", amount, "note", "invariant op " + op), headers), String.class);
        assertThat(response.getStatusCode()).as("adjustment %s at op %d: %s", amount, op, response.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    private void emptyWallet(UUID orgId, int op) {
        BigDecimal balance = wallet(orgId).getBalance();
        if (balance.signum() != 0) {
            adjust(orgId, balance.negate(), op);
        }
        assertThat(wallet(orgId).getBalance()).isEqualByComparingTo("0");
    }

    private static BigDecimal cents(Random random, int min, int max) {
        return BigDecimal.valueOf(min + random.nextInt(max - min + 1), 2);
    }

    private boolean isPrepaid(UUID orgId) {
        return billingModeService.modeFor(orgId) == BillingMode.PREPAID;
    }

    private Wallet wallet(UUID orgId) {
        return walletRepository.findByOrgId(orgId).orElseThrow();
    }

    private BigDecimal available(UUID orgId) {
        Wallet wallet = wallet(orgId);
        return wallet.getBalance().subtract(wallet.getReserved());
    }

    private BigDecimal orgBalance(UUID orgId, LedgerAccountKind kind) {
        return accounts.findByOrgIdAndKind(orgId, kind)
                .map(a -> ledgerService.balanceOf(a.getId()))
                .orElse(BigDecimal.ZERO);
    }

    private Map<LedgerAccountKind, BigDecimal> platformBalances() {
        Map<LedgerAccountKind, BigDecimal> result = new EnumMap<>(LedgerAccountKind.class);
        for (LedgerAccountKind kind : SPLIT_PLATFORM_KINDS) {
            result.put(kind, ledgerService.balanceOf(ledgerAccounts.platformAccount(kind)));
        }
        return result;
    }
}
