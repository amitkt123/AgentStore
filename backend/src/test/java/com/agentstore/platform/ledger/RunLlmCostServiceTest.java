package com.agentstore.platform.ledger;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunLifecycleService;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.execution.RunStepKind;
import com.agentstore.platform.execution.RunTokenService;
import com.agentstore.platform.execution.TerminalReason;
import com.agentstore.platform.llm.LlmCall;
import com.agentstore.platform.llm.LlmCallRepository;
import com.agentstore.platform.llm.LlmCallStatus;
import com.agentstore.platform.llm.LlmFixtures;
import com.agentstore.platform.llm.LlmSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunLlmCostServiceTest extends AbstractIntegrationTest {

    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    LedgerService ledgerService;
    @Autowired
    LedgerAccounts ledgerAccounts;
    @Autowired
    LedgerAccountRepository accounts;
    @Autowired
    RunLlmCostService runLlmCostService;
    @Autowired
    LlmCallRepository llmCalls;
    @Autowired
    RunRepository runRepository;
    @Autowired
    RunLifecycleService lifecycle;
    @Autowired
    LlmFixtures llmFixtures;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;

    Run buyerRun() {
        ExecutionFixtures.Scenario scenario = fixtures.liveTierA(new BigDecimal("100000.00"), new BigDecimal("1000.00"));
        return fixtures.run(scenario, RunStatus.RUNNING, "sbx-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());
    }

    UUID developerOrg(Run run) {
        return run.getAgentVersion().getAgent().getDeveloperOrg().getId();
    }

    void llmCall(Run run, LlmSource source, String cost) {
        llmFixtures.ensureModels();
        LlmCall call = new LlmCall(UUID.randomUUID(), run.getId(), LlmFixtures.CLAUDE, source, LlmCallStatus.OK, "h-" + UUID.randomUUID());
        call.setCost(new BigDecimal(cost));
        llmCalls.save(call);
    }

    BigDecimal payable(UUID orgId) {
        return accounts.findByOrgIdAndKind(orgId, LedgerAccountKind.DEVELOPER_PAYABLE)
                .map(a -> ledgerService.balanceOf(a.getId())).orElse(BigDecimal.ZERO);
    }

    long transactionCount(Run run) {
        return jdbc.queryForObject("select count(*) from ledger_transaction where run_id = ?", Long.class, run.getId());
    }

    void complete(Run run) {
        lifecycle.recordEvent(run.getId(), RunStepKind.COMPLETE, Map.of());
    }

    @Test
    void platformLlmCostIsDeductedOnce() {
        Run run = buyerRun();
        llmCall(run, LlmSource.PLATFORM, "1.234");
        llmCall(run, LlmSource.PLATFORM, "2.000");
        BigDecimal llmCostBefore = ledgerService.balanceOf(ledgerAccounts.platformAccount(LedgerAccountKind.PLATFORM_LLM_COST));

        complete(run);

        assertThat(payable(developerOrg(run))).isEqualByComparingTo("-3.23");
        assertThat(ledgerService.balanceOf(ledgerAccounts.platformAccount(LedgerAccountKind.PLATFORM_LLM_COST))
                .subtract(llmCostBefore)).isEqualByComparingTo("3.23");
        assertThat(transactionCount(run)).isEqualTo(1L);

        Run finished = runRepository.findById(run.getId()).orElseThrow();
        tx.executeWithoutResult(s -> runLlmCostService.post(finished));
        tx.executeWithoutResult(s -> runLlmCostService.post(finished));

        assertThat(payable(developerOrg(run))).isEqualByComparingTo("-3.23");
        assertThat(transactionCount(run)).isEqualTo(1L);
    }

    @Test
    void postRequiresAnExistingTransaction() {
        Run run = buyerRun();
        assertThatThrownBy(() -> runLlmCostService.post(run))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void byokMockReplayAreIgnored() {
        Run run = buyerRun();
        llmCall(run, LlmSource.BYOK_PROXY, "4.00");
        llmCall(run, LlmSource.MOCK, "5.00");
        llmCall(run, LlmSource.REPLAY, "6.00");

        complete(run);

        assertThat(transactionCount(run)).isZero();
        assertThat(payable(developerOrg(run))).isEqualByComparingTo("0");
    }

    @Test
    void unchargedFailedRunStillDeducts() {
        Run run = buyerRun();
        llmCall(run, LlmSource.PLATFORM, "5.00");

        boolean ended = lifecycle.terminate(run.getId(), RunStatus.RUNNING, RunStatus.TIMED_OUT,
                TerminalReason.TIMED_OUT, RunStepKind.TIMEOUT, Map.of());

        assertThat(ended).isTrue();
        assertThat(payable(developerOrg(run))).isEqualByComparingTo("-5.00");
    }

    @Test
    void sandboxRunNotDeducted() {
        Run buyer = buyerRun();
        Run sandbox = new Run(null, null, buyer.getAgentVersion(), RunStatus.RUNNING, new BigDecimal("100.00"),
                BigDecimal.ZERO, "sbx-" + UUID.randomUUID(), RunTokenService.hash("tok-" + UUID.randomUUID()));
        sandbox.setMode(RunMode.SANDBOX);
        sandbox.setInitiatedByOrgId(developerOrg(buyer));
        sandbox = runRepository.save(sandbox);
        llmCall(sandbox, LlmSource.PLATFORM, "7.00");

        lifecycle.recordEvent(sandbox.getId(), RunStepKind.COMPLETE, Map.of());

        assertThat(transactionCount(sandbox)).isZero();
        assertThat(payable(developerOrg(buyer))).isEqualByComparingTo("0");
    }
}
