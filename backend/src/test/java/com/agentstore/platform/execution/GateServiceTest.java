package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GateServiceTest extends AbstractIntegrationTest {

    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    GateService gateService;
    @Autowired
    RunRepository runRepository;
    @Autowired
    GateRequestRepository gateRepository;
    @Autowired
    RunStepRepository stepRepository;
    @Autowired
    RunReaper reaper;
    @Autowired
    FakeSandboxProvider sandbox;

    private Run running(String token) {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        return fixtures.run(scenario, RunStatus.RUNNING, "sbx-" + token, token);
    }

    private GateRequest open(Run run, int timeoutMinutes) {
        return gateService.open(run.getId(), "post_journal_entries", timeoutMinutes, "Post 12 entries",
                "{\"rows\":12}", "[\"journal_entries\"]");
    }

    private Run reload(Run run) {
        return runRepository.findById(run.getId()).orElseThrow();
    }

    @Test
    void openingAGatePausesTheRunAndRecordsAPlatformStep() {
        Run run = running("tok-gs-1");

        GateRequest gate = open(run, 5);

        assertThat(gate.getStatus()).isEqualTo(GateStatus.PENDING);
        assertThat(gate.getExpiresAt()).isAfter(Instant.now().plusSeconds(240)).isBefore(Instant.now().plusSeconds(360));
        Run updated = reload(run);
        assertThat(updated.getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(updated.getAwaitingSince()).isNotNull();
        assertThat(stepRepository.findByRunIdOrderByCreatedAtAsc(run.getId())).singleElement().satisfies(s -> {
            assertThat(s.getKind()).isEqualTo(RunStepKind.GATE_REQUESTED);
            assertThat(s.getSource()).isEqualTo(RunStepSource.PLATFORM);
            assertThat(s.getDetail()).contains(gate.getId().toString());
        });
    }

    @Test
    void aSecondGateWhileOneIsOpenIsRefused() {
        Run run = running("tok-gs-2");
        open(run, 5);

        assertThatThrownBy(() -> open(run, 5)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(gateRepository.findAll().stream().filter(g -> g.getRun().getId().equals(run.getId()))).hasSize(1);
    }

    @Test
    void openingAGateOnATerminalRunIsRefused() {
        Run run = running("tok-gs-3");
        run.setStatus(RunStatus.COMPLETED);
        runRepository.save(run);

        assertThatThrownBy(() -> open(run, 5)).isInstanceOf(RunTerminatedException.class);
    }

    private GateRequest openAndLapse(Run run) {
        GateRequest gate = open(run, 5);
        gate.setExpiresAt(Instant.now().minusSeconds(1));
        gateRepository.save(gate);
        return gate;
    }

    @Test
    void refusesToApproveAGateWhoseExpiryHasPassed() {
        Run run = running("tok-gs-exp-1");
        GateRequest gate = openAndLapse(run);

        assertThatThrownBy(() -> gateService.decide(run.getId(), gate.getId(), true, UUID.randomUUID(), null, DecisionChannel.WEB))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(gateRepository.findById(gate.getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.PENDING);
        assertThat(reload(run).getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
    }

    @Test
    void refusesARejectionPastExpiry() {
        Run run = running("tok-gs-exp-2");
        GateRequest gate = openAndLapse(run);

        assertThatThrownBy(() -> gateService.decide(run.getId(), gate.getId(), false, UUID.randomUUID(), null, DecisionChannel.WEB))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(gateRepository.findById(gate.getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.PENDING);
        assertThat(reload(run).getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
    }

    @Test
    void approvingMarksTheGateAndResumesTheRun() {
        Run run = running("tok-gs-4");
        GateRequest gate = open(run, 5);
        UUID approver = UUID.randomUUID();

        Run result = gateService.decide(run.getId(), gate.getId(), true, approver, "looks fine", DecisionChannel.WEB);

        assertThat(result.getStatus()).isEqualTo(RunStatus.RUNNING);
        GateRequest decided = gateRepository.findById(gate.getId()).orElseThrow();
        assertThat(decided.getStatus()).isEqualTo(GateStatus.APPROVED);
        assertThat(decided.getDecidedByUserId()).isEqualTo(approver);
        assertThat(decided.getDecisionNote()).isEqualTo("looks fine");
        assertThat(decided.getDecidedAt()).isNotNull();
        assertThat(stepRepository.findByRunIdOrderByCreatedAtAsc(run.getId())).extracting(RunStep::getKind)
                .containsExactly(RunStepKind.GATE_REQUESTED, RunStepKind.GATE_DECIDED);
    }

    @Test
    void rejectingMarksTheGateCancelsTheRunAndKillsTheSandbox() {
        Run run = running("tok-gs-5");
        GateRequest gate = open(run, 5);

        Run result = gateService.decide(run.getId(), gate.getId(), false, UUID.randomUUID(), "too risky", DecisionChannel.WEB);

        assertThat(result.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(gateRepository.findById(gate.getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.REJECTED);
        assertThat(sandbox.wasKilled("sbx-tok-gs-5")).isTrue();
    }

    @Test
    void aRunWithoutAGateRowKeepsTheLegacyApprovalFlow() {
        Run run = running("tok-gs-6");
        run.setStatus(RunStatus.AWAITING_APPROVAL);
        runRepository.save(run);

        Run result = gateService.decide(run.getId(), null, true, UUID.randomUUID(), null, DecisionChannel.WEB);

        assertThat(result.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(gateRepository.findAll().stream().filter(g -> g.getRun().getId().equals(run.getId()))).isEmpty();
        assertThat(stepRepository.findByRunIdOrderByCreatedAtAsc(run.getId())).isEmpty();
    }

    @Test
    void aGateIdFromAnotherRunIsNotFoundAndDecidingTwiceIsAConflict() {
        Run run = running("tok-gs-7");
        Run other = running("tok-gs-7b");
        GateRequest gate = open(run, 5);

        assertThatThrownBy(() -> gateService.decide(other.getId(), gate.getId(), true, UUID.randomUUID(), null, DecisionChannel.WEB))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        gateService.decide(run.getId(), gate.getId(), true, UUID.randomUUID(), null, DecisionChannel.WEB);
        assertThatThrownBy(() -> gateService.decide(run.getId(), gate.getId(), true, UUID.randomUUID(), null, DecisionChannel.WEB))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void awaitReturnsAtOnceWhenDecidedAndPendingAfterTheWaitOtherwise() throws Exception {
        Run run = running("tok-gs-8");
        GateRequest gate = open(run, 5);

        long started = System.nanoTime();
        GateRequest stillPending = gateService.await(run.getId(), gate.getId(), Duration.ofMillis(300));
        assertThat(stillPending.getStatus()).isEqualTo(GateStatus.PENDING);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(250));

        gateService.decide(run.getId(), gate.getId(), true, UUID.randomUUID(), null, DecisionChannel.WEB);
        started = System.nanoTime();
        assertThat(gateService.await(run.getId(), gate.getId(), Duration.ofSeconds(20)).getStatus()).isEqualTo(GateStatus.APPROVED);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void theReaperExpiresAGateFromItsOwnTimeoutNotTheGlobalOne() {
        Run shortGate = running("tok-gs-9");
        Run longGate = running("tok-gs-9b");
        GateRequest shortOne = open(shortGate, 1);
        GateRequest longOne = open(longGate, 60);

        // 40 minutes on: past the 1-minute gate and the global 30-minute default, but inside the 60-minute gate.
        reaper.reapAt(Instant.now().plus(Duration.ofMinutes(40)));

        assertThat(reload(shortGate).getStatus()).isEqualTo(RunStatus.EXPIRED);
        assertThat(gateRepository.findById(shortOne.getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.EXPIRED);
        assertThat(stepRepository.findByRunIdOrderByCreatedAtAsc(shortGate.getId())).extracting(RunStep::getKind)
                .contains(RunStepKind.TIMEOUT);
        assertThat(reload(longGate).getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(gateRepository.findById(longOne.getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.PENDING);
    }

    @Test
    void decisionAndExpiryNeverLeaveAMixedState() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 15; i++) {
            Run run = running("tok-gs-race-" + i);
            GateRequest gate = open(run, 1);
            CountDownLatch start = new CountDownLatch(1);
            Future<?> decide = pool.submit(() -> {
                start.await();
                try {
                    gateService.decide(run.getId(), gate.getId(), true, UUID.randomUUID(), null, DecisionChannel.WEB);
                } catch (ResponseStatusException expected) {
                    // lost the race to the expiry
                }
                return null;
            });
            Future<?> expire = pool.submit(() -> {
                start.await();
                gateService.expire(run.getId(), RunStatus.AWAITING_APPROVAL, TerminalReason.GATE_EXPIRED, Map.of("summary", "expired"));
                return null;
            });
            start.countDown();
            decide.get();
            expire.get();

            GateStatus gateStatus = gateRepository.findById(gate.getId()).orElseThrow().getStatus();
            RunStatus runStatus = reload(run).getStatus();
            boolean approved = gateStatus == GateStatus.APPROVED && runStatus == RunStatus.RUNNING;
            boolean expired = gateStatus == GateStatus.EXPIRED && runStatus == RunStatus.EXPIRED;
            assertThat(approved || expired).as("gate %s with run %s", gateStatus, runStatus).isTrue();
        }
        pool.shutdown();
    }

    @Test
    void anExpiryThatLostToADecisionChangesNothing() {
        Run run = running("tok-gs-10");
        GateRequest gate = open(run, 1);
        gateService.decide(run.getId(), gate.getId(), true, UUID.randomUUID(), null, DecisionChannel.WEB);

        boolean expired = gateService.expire(run.getId(), RunStatus.AWAITING_APPROVAL, TerminalReason.GATE_EXPIRED, Map.of());

        assertThat(expired).isFalse();
        assertThat(reload(run).getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(gateRepository.findById(gate.getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.APPROVED);
    }
}
