package com.agentstore.platform.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentstore.platform.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class TerminalReasonTest extends AbstractIntegrationTest {

    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    RunLifecycleService lifecycle;
    @Autowired
    GateService gateService;
    @Autowired
    RunRepository runs;
    @Autowired
    JdbcTemplate jdbc;

    private Run running(String tag) {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        return fixtures.run(scenario, RunStatus.RUNNING, "sbx-" + tag, "tok-" + tag);
    }

    private Run reload(Run run) {
        return runs.findById(run.getId()).orElseThrow();
    }

    @Test
    void completeSetsFinishedAndSummary() {
        Run run = running("tr-1");
        lifecycle.complete(run.getId(), Map.of("summary", "All done"));
        Run done = reload(run);
        assertThat(done.getTerminalReason()).isEqualTo(TerminalReason.FINISHED);
        assertThat(done.getSummary()).isEqualTo("All done");
    }

    @Test
    void agentFailIsAgentFailed() {
        Run run = running("tr-2");
        lifecycle.fail(run.getId(), Map.of("summary", "boom"));
        assertThat(reload(run).getTerminalReason()).isEqualTo(TerminalReason.AGENT_FAILED);
    }

    @Test
    void rejectingAGateIsGateRejected() {
        Run run = running("tr-3");
        gateService.open(run.getId(), "post_journal_entries", 5, "Post", "{}", "[]");
        gateService.decide(run.getId(), null, false, UUID.randomUUID(), "no");
        Run done = reload(run);
        assertThat(done.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(done.getTerminalReason()).isEqualTo(TerminalReason.GATE_REJECTED);
    }

    @Test
    void gateExpiryIsGateExpired() {
        Run run = running("tr-4");
        gateService.open(run.getId(), "post_journal_entries", 5, "Post", "{}", "[]");
        gateService.expire(run.getId(), RunStatus.AWAITING_APPROVAL, TerminalReason.GATE_EXPIRED,
                Map.of("summary", "No approval decision in time; run stopped"));
        Run done = reload(run);
        assertThat(done.getStatus()).isEqualTo(RunStatus.EXPIRED);
        assertThat(done.getTerminalReason()).isEqualTo(TerminalReason.GATE_EXPIRED);
    }

    @Test
    void timeoutAndSandboxLostUseTheReasonPassedByTheCaller() {
        Run a = running("tr-5");
        lifecycle.terminate(a.getId(), RunStatus.RUNNING, RunStatus.TIMED_OUT, TerminalReason.TIMED_OUT,
                RunStepKind.TIMEOUT, Map.of("summary", "Run exceeded its time limit"));
        assertThat(reload(a).getTerminalReason()).isEqualTo(TerminalReason.TIMED_OUT);

        Run b = running("tr-6");
        lifecycle.terminate(b.getId(), RunStatus.RUNNING, RunStatus.FAILED, TerminalReason.SANDBOX_LOST,
                RunStepKind.FAILED, Map.of("summary", "Agent stopped without reporting completion"));
        assertThat(reload(b).getTerminalReason()).isEqualTo(TerminalReason.SANDBOX_LOST);
    }

    @Test
    void repeatedViolationsAreScopeViolations() {
        Run run = running("tr-7");
        for (int i = 0; i < RunLifecycleService.MAX_PRODUCTION_VIOLATIONS; i++) {
            lifecycle.recordViolation(run.getId(), Map.of("attempted", "read", "target", "payroll"));
        }
        assertThat(reload(run).getTerminalReason()).isEqualTo(TerminalReason.SCOPE_VIOLATIONS);
    }

    @Test
    void capReachedIsCostCapReached() {
        var scenario = fixtures.liveTierA(new BigDecimal("1.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(scenario, RunStatus.RUNNING, "sbx-tr-8", "tok-tr-8");
        lifecycle.recordUsage(run.getId(), new BigDecimal("1000000"), Map.of("quantity", 1000000, "unit", "page"));
        assertThat(reload(run).getTerminalReason()).isEqualTo(TerminalReason.COST_CAP_REACHED);
    }

    @Test
    void stepsWithABlockUpdateReachedBlockAndNonTerminalRunsHaveNoReason() {
        Run run = running("tr-9");
        lifecycle.recordStep(run.getId(), RunStepKind.LLM_CALL, RunStepSource.PLATFORM, Map.of("tokens", 5));
        Run r = reload(run);
        assertThat(r.getReachedBlock()).isEqualTo("llm");
        assertThat(r.getTerminalReason()).isNull();
    }

    @Test
    void backfillSqlMapsStatusAndClosingStepToTheRightReason() {
        Run completed = running("tr-10");
        Run capped = running("tr-11");
        Run cancelled = running("tr-12");
        jdbc.update("UPDATE run SET status = 'COMPLETED' WHERE id = ?", completed.getId());
        jdbc.update("UPDATE run SET status = 'FAILED' WHERE id = ?", capped.getId());
        jdbc.update("INSERT INTO run_step (id, run_id, kind, detail, source, created_at) VALUES (?, ?, 'COST_CAP_REACHED', '{}'::jsonb, 'PLATFORM', now())",
                UUID.randomUUID(), capped.getId());
        jdbc.update("UPDATE run SET status = 'CANCELLED' WHERE id = ?", cancelled.getId());
        jdbc.update("INSERT INTO run_step (id, run_id, kind, detail, source, created_at) VALUES (?, ?, 'CANCELLED', '{}'::jsonb, 'PLATFORM', now())",
                UUID.randomUUID(), cancelled.getId());

        // the same statements V38 runs, restricted to these three runs
        jdbc.update("UPDATE run SET terminal_reason = 'FINISHED' WHERE status = 'COMPLETED' AND id = ?", completed.getId());
        jdbc.update("""
                UPDATE run r SET terminal_reason = CASE
                    WHEN EXISTS (SELECT 1 FROM run_step s WHERE s.run_id = r.id AND s.kind = 'CANCELLED') THEN 'CANCELLED'
                    ELSE 'GATE_REJECTED' END WHERE r.status = 'CANCELLED' AND r.id = ?""", cancelled.getId());
        jdbc.update("""
                UPDATE run r SET terminal_reason = CASE
                    WHEN EXISTS (SELECT 1 FROM run_step s WHERE s.run_id = r.id AND s.kind = 'COST_CAP_REACHED') THEN 'COST_CAP_REACHED'
                    ELSE 'AGENT_FAILED' END WHERE r.status = 'FAILED' AND r.id = ?""", capped.getId());

        assertThat(reload(completed).getTerminalReason()).isEqualTo(TerminalReason.FINISHED);
        assertThat(reload(capped).getTerminalReason()).isEqualTo(TerminalReason.COST_CAP_REACHED);
        assertThat(reload(cancelled).getTerminalReason()).isEqualTo(TerminalReason.CANCELLED);
    }
}
