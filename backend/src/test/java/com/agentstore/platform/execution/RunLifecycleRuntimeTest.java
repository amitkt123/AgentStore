package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunLifecycleRuntimeTest extends AbstractIntegrationTest {

    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    RunLifecycleService lifecycle;
    @Autowired
    RunRepository runRepository;
    @Autowired
    RunStepRepository stepRepository;
    @Autowired
    FakeSandboxProvider sandbox;

    private Run running(String token, String cap) {
        var scenario = fixtures.liveTierA(new BigDecimal(cap), new BigDecimal("1000.00"));
        return fixtures.run(scenario, RunStatus.RUNNING, "sbx-" + token, token);
    }

    private List<RunStep> steps(Run run) {
        return stepRepository.findByRunIdOrderByCreatedAtAsc(run.getId());
    }

    @Test
    void recordStepStoresAPlatformStepAndRefreshesTheHeartbeat() {
        Run run = running("tok-lr-1", "500.00");

        lifecycle.recordStep(run.getId(), RunStepKind.DATA_READ, RunStepSource.PLATFORM, Map.of("resource", "r", "count", 3));

        assertThat(steps(run)).singleElement().satisfies(s -> {
            assertThat(s.getKind()).isEqualTo(RunStepKind.DATA_READ);
            assertThat(s.getSource()).isEqualTo(RunStepSource.PLATFORM);
        });
        assertThat(runRepository.findById(run.getId()).orElseThrow().getLastHeartbeatAt()).isNotNull();
    }

    @Test
    void aTerminalRunRejectsEveryRuntimeOperation() {
        Run run = running("tok-lr-2", "500.00");
        lifecycle.complete(run.getId(), Map.of("summary", "done"));

        assertThatThrownBy(() -> lifecycle.recordStep(run.getId(), RunStepKind.DATA_READ, RunStepSource.PLATFORM, Map.of()))
                .isInstanceOf(RunTerminatedException.class);
        assertThatThrownBy(() -> lifecycle.heartbeat(run.getId())).isInstanceOf(RunTerminatedException.class);
        assertThatThrownBy(() -> lifecycle.recordViolation(run.getId(), Map.of())).isInstanceOf(RunTerminatedException.class);
        assertThatThrownBy(() -> lifecycle.recordUsage(run.getId(), BigDecimal.ONE, Map.of())).isInstanceOf(RunTerminatedException.class);
        assertThatThrownBy(() -> lifecycle.fail(run.getId(), Map.of())).isInstanceOf(RunTerminatedException.class);
    }

    @Test
    void thirdProductionViolationStopsTheRun() {
        Run run = running("tok-lr-3", "500.00");

        assertThat(lifecycle.recordViolation(run.getId(), Map.of("summary", "one"))).isFalse();
        assertThat(lifecycle.recordViolation(run.getId(), Map.of("summary", "two"))).isFalse();
        assertThat(lifecycle.recordViolation(run.getId(), Map.of("summary", "three"))).isTrue();

        Run updated = runRepository.findById(run.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(updated.getViolationCount()).isEqualTo(3);
        assertThat(sandbox.wasKilled("sbx-tok-lr-3")).isTrue();
        assertThat(steps(run)).extracting(RunStep::getKind).containsExactly(
                RunStepKind.SCOPE_VIOLATION, RunStepKind.SCOPE_VIOLATION, RunStepKind.SCOPE_VIOLATION, RunStepKind.FAILED);
        assertThat(steps(run)).allMatch(s -> s.getSource() == RunStepSource.PLATFORM);
    }

    @Test
    void sandboxRunsReportEveryViolationAndKeepGoing() {
        Run run = running("tok-lr-4", "500.00");
        run.setMode(RunMode.SANDBOX);
        runRepository.save(run);

        for (int i = 0; i < 5; i++) {
            assertThat(lifecycle.recordViolation(run.getId(), Map.of("summary", "v" + i))).isFalse();
        }

        Run updated = runRepository.findById(run.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(updated.getViolationCount()).isEqualTo(5);
    }

    @Test
    void concurrentViolationsStopTheRunExactlyOnce() throws Exception {
        Run run = running("tok-lr-5", "500.00");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    return lifecycle.recordViolation(run.getId(), Map.of("summary", "race"));
                } catch (RunTerminatedException e) {
                    return null;   // arrived after the run was already stopped
                }
            }));
        }
        start.countDown();
        int stopped = 0;
        for (Future<Boolean> f : results) {
            if (Boolean.TRUE.equals(f.get())) stopped++;
        }
        pool.shutdown();

        Run updated = runRepository.findById(run.getId()).orElseThrow();
        assertThat(stopped).isEqualTo(1);
        assertThat(updated.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(updated.getViolationCount()).isEqualTo(3);
        assertThat(steps(run)).filteredOn(s -> s.getKind() == RunStepKind.SCOPE_VIOLATION).hasSize(3);
        assertThat(steps(run)).filteredOn(s -> s.getKind() == RunStepKind.FAILED).hasSize(1);
    }

    @Test
    void usageChargesThePlatformPriceAndRecordsAPlatformStep() {
        Run run = running("tok-lr-6", "500.00");

        UsageOutcome outcome = lifecycle.recordUsage(run.getId(), new BigDecimal("3"), Map.of("unit", "reconciled_transaction"));

        assertThat(outcome.charged()).isEqualByComparingTo("6.00");
        assertThat(outcome.totalCharged()).isEqualByComparingTo("6.00");
        assertThat(outcome.costCapRemaining()).isEqualByComparingTo("494.00");
        assertThat(outcome.capReached()).isFalse();
        assertThat(steps(run)).singleElement().satisfies(s -> {
            assertThat(s.getKind()).isEqualTo(RunStepKind.USAGE);
            assertThat(s.getSource()).isEqualTo(RunStepSource.PLATFORM);
        });
    }

    @Test
    void usageBeyondTheCapChargesOnlyTheRemainderAndStopsTheRun() {
        Run run = running("tok-lr-7", "10.00");

        UsageOutcome outcome = lifecycle.recordUsage(run.getId(), new BigDecimal("6"), Map.of());   // 12.00 requested

        assertThat(outcome.capReached()).isTrue();
        assertThat(outcome.charged()).isEqualByComparingTo("10.00");
        Run updated = runRepository.findById(run.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(sandbox.wasKilled("sbx-tok-lr-7")).isTrue();
        assertThat(steps(run)).extracting(RunStep::getKind)
                .containsExactly(RunStepKind.USAGE, RunStepKind.COST_CAP_REACHED);
    }

    @Test
    void nonPositiveUsageIsRejectedWithoutChargingAnything() {
        Run run = running("tok-lr-8", "500.00");

        assertThatThrownBy(() -> lifecycle.recordUsage(run.getId(), BigDecimal.ZERO, Map.of()))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> lifecycle.recordUsage(run.getId(), new BigDecimal("-2"), Map.of()))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(runRepository.findById(run.getId()).orElseThrow().getTotalCharged()).isEqualByComparingTo("0");
        assertThat(steps(run)).isEmpty();
    }

    @Test
    void completeFinishesTheRunAndKillsTheSandbox() {
        Run run = running("tok-lr-9", "500.00");

        lifecycle.complete(run.getId(), Map.of("summary", "all done"));

        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(sandbox.wasKilled("sbx-tok-lr-9")).isTrue();
        assertThat(steps(run)).singleElement().satisfies(s -> {
            assertThat(s.getKind()).isEqualTo(RunStepKind.COMPLETE);
            assertThat(s.getSource()).isEqualTo(RunStepSource.PLATFORM);
        });
    }

    @Test
    void anAgentCannotCompleteWhileAGateIsAwaitingApproval() {
        Run run = running("tok-lr-10", "500.00");
        run.setStatus(RunStatus.AWAITING_APPROVAL);
        runRepository.save(run);

        assertThatThrownBy(() -> lifecycle.complete(run.getId(), Map.of()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("awaiting approval");
        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
    }

    @Test
    void failRecordsAPlatformFailedStep() {
        Run run = running("tok-lr-11", "500.00");

        lifecycle.fail(run.getId(), Map.of("summary", "bank API down"));

        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(steps(run)).singleElement().satisfies(s -> assertThat(s.getKind()).isEqualTo(RunStepKind.FAILED));
    }

    @Test
    void agentLogsAreStoredAsAgentReportedSteps() {
        Run run = running("tok-lr-12", "500.00");

        lifecycle.recordAgentLogs(run.getId(), List.of(Map.of("level", "info", "message", "a"), Map.of("level", "warn", "message", "b")));

        assertThat(steps(run)).hasSize(2).allSatisfy(s -> {
            assertThat(s.getKind()).isEqualTo(RunStepKind.AGENT_LOG);
            assertThat(s.getSource()).isEqualTo(RunStepSource.AGENT);
        });
    }
}
