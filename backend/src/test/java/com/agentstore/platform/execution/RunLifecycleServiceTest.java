package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunLifecycleServiceTest extends AbstractIntegrationTest {

    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    RunLifecycleService lifecycle;
    @Autowired
    RunRepository runRepository;
    @Autowired
    RunStepRepository runStepRepository;
    @Autowired
    FakeSandboxProvider fakeSandboxProvider;

    ExecutionFixtures.Scenario scenario() {
        return fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
    }

    String sandbox() {
        return "fake-sandbox-" + UUID.randomUUID();
    }

    @Test
    void markStartedMovesPendingRunToRunning() {
        Run run = fixtures.run(scenario(), RunStatus.PENDING, null, "tok-" + UUID.randomUUID());
        String sandboxId = sandbox();

        lifecycle.markStarted(run.getId(), sandboxId);

        Run after = runRepository.findById(run.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(after.getSandboxId()).isEqualTo(sandboxId);
    }

    @Test
    void agentFinishingBeforeSandboxIsRecordedStillGetsItsSandboxKilled() {
        Run run = fixtures.run(scenario(), RunStatus.PENDING, null, "tok-" + UUID.randomUUID());
        lifecycle.recordEvent(run.getId(), RunStepKind.COMPLETE, Map.of());
        String sandboxId = sandbox();

        lifecycle.markStarted(run.getId(), sandboxId);

        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fakeSandboxProvider.wasKilled(sandboxId)).isTrue();
    }

    @Test
    void aUsageStepCarriesTheAmountCharged() {
        Run run = fixtures.run(scenario(), RunStatus.RUNNING, sandbox(), "tok-" + UUID.randomUUID());

        lifecycle.recordUsage(run.getId(), new BigDecimal("2"), Map.of());   // 2 units at the fixture's unit price 2.00

        var usage = runStepRepository.findByRunIdOrderByCreatedAtAsc(run.getId()).stream()
                .filter(s -> s.getKind() == RunStepKind.USAGE).findFirst().orElseThrow();
        assertThat(usage.getDetail()).containsPattern("\"amount\"\\s*:\\s*4(\\.00)?\\b");
    }

    @Test
    void terminateIsIdempotentAndRecordsOneStep() {
        String sandboxId = sandbox();
        Run run = fixtures.run(scenario(), RunStatus.RUNNING, sandboxId, "tok-" + UUID.randomUUID());

        assertThat(lifecycle.terminate(run.getId(), RunStatus.TIMED_OUT, TerminalReason.TIMED_OUT, RunStepKind.TIMEOUT,
                Map.of("summary", "Run exceeded its time limit"))).isTrue();
        assertThat(lifecycle.terminate(run.getId(), RunStatus.FAILED, TerminalReason.AGENT_FAILED, RunStepKind.FAILED, Map.of())).isFalse();

        Run after = runRepository.findById(run.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RunStatus.TIMED_OUT);
        assertThat(after.getCompletedAt()).isNotNull();
        assertThat(fakeSandboxProvider.wasKilled(sandboxId)).isTrue();
        assertThat(runStepRepository.findByRunIdOrderByCreatedAtAsc(run.getId()))
                .extracting(RunStep::getKind).containsExactly(RunStepKind.TIMEOUT);
    }

    @Test
    void eventsForTerminalRunsAreRejected() {
        Run run = fixtures.run(scenario(), RunStatus.COMPLETED, sandbox(), "tok-" + UUID.randomUUID());

        assertThatThrownBy(() -> lifecycle.recordEvent(run.getId(), RunStepKind.READ, Map.of()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void agentCannotReportPlatformOnlyStepKinds() {
        Run run = fixtures.run(scenario(), RunStatus.RUNNING, sandbox(), "tok-" + UUID.randomUUID());

        assertThatThrownBy(() -> lifecycle.recordEvent(run.getId(), RunStepKind.TIMEOUT, Map.of()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void gateEventRecordsWhenTheApprovalWaitStarted() {
        Run run = fixtures.run(scenario(), RunStatus.RUNNING, sandbox(), "tok-" + UUID.randomUUID());

        lifecycle.recordEvent(run.getId(), RunStepKind.GATE, Map.of("trigger", "X"));

        Run after = runRepository.findById(run.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(after.getAwaitingSince()).isNotNull();
        assertThat(after.getLastHeartbeatAt()).isNotNull();
    }

    @Test
    void approvingClearsTheWaitAndRejectingKills() {
        String sandboxId = sandbox();
        Run run = fixtures.run(scenario(), RunStatus.RUNNING, sandboxId, "tok-" + UUID.randomUUID());
        lifecycle.recordEvent(run.getId(), RunStepKind.GATE, Map.of());

        assertThat(lifecycle.approveGate(run.getId()).getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(runRepository.findById(run.getId()).orElseThrow().getAwaitingSince()).isNull();

        lifecycle.recordEvent(run.getId(), RunStepKind.GATE, Map.of());
        assertThat(lifecycle.rejectGate(run.getId()).getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(fakeSandboxProvider.wasKilled(sandboxId)).isTrue();
    }
}
