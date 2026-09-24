package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.ledger.Wallet;
import com.agentstore.platform.ledger.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunReaperTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    RunReaper reaper;
    @Autowired
    RunLifecycleService runLifecycleService;
    @Autowired
    RunRepository runRepository;
    @Autowired
    RunStepRepository runStepRepository;
    @Autowired
    WalletRepository walletRepository;
    @Autowired
    FakeSandboxProvider fakeSandboxProvider;

    Instant now = Instant.now();

    ExecutionFixtures.Scenario scenario() {
        return fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));
    }

    Run run(ExecutionFixtures.Scenario s, RunStatus status, String sandboxId, Instant deadline, Instant awaitingSince) {
        Run run = fixtures.run(s, status, sandboxId, "tok-" + UUID.randomUUID());
        run.setDeadlineAt(deadline);
        run.setAwaitingSince(awaitingSince);
        return runRepository.save(run);
    }

    Run reload(Run run) {
        return runRepository.findById(run.getId()).orElseThrow();
    }

    String sandbox() {
        return "fake-sandbox-" + UUID.randomUUID();
    }

    @Test
    void runPastItsDeadlineIsTimedOutAndKilled() {
        String sandboxId = sandbox();
        Run run = run(scenario(), RunStatus.RUNNING, sandboxId, now.minusSeconds(1), null);

        reaper.reapAt(now);

        assertThat(reload(run).getStatus()).isEqualTo(RunStatus.TIMED_OUT);
        assertThat(fakeSandboxProvider.wasKilled(sandboxId)).isTrue();
        assertThat(runStepRepository.findByRunIdOrderByCreatedAtAsc(run.getId()))
                .extracting(RunStep::getKind).containsExactly(RunStepKind.TIMEOUT);
    }

    @Test
    void approvalWaitLongerThanTheGateTimeoutExpires() {
        Run run = run(scenario(), RunStatus.AWAITING_APPROVAL, sandbox(), now.plusSeconds(600),
                now.minus(Duration.ofMinutes(31)));

        reaper.reapAt(now);

        assertThat(reload(run).getStatus()).isEqualTo(RunStatus.EXPIRED);
    }

    @Test
    void approvalWaitPastTheRunDeadlineExpiresRatherThanTimesOut() {
        Run run = run(scenario(), RunStatus.AWAITING_APPROVAL, sandbox(), now.minusSeconds(1), now.minusSeconds(60));

        reaper.reapAt(now);

        assertThat(reload(run).getStatus()).isEqualTo(RunStatus.EXPIRED);
    }

    @Test
    void runningRunWhoseSandboxExitedWithoutReportingIsFailed() {
        String sandboxId = sandbox();
        Run run = run(scenario(), RunStatus.RUNNING, sandboxId, now.plusSeconds(600), null);
        fakeSandboxProvider.markExited(sandboxId);

        reaper.reapAt(now);

        assertThat(reload(run).getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void healthyRunsAreLeftAlone() {
        Run running = run(scenario(), RunStatus.RUNNING, sandbox(), now.plusSeconds(600), null);
        Run waiting = run(scenario(), RunStatus.AWAITING_APPROVAL, sandbox(), now.plusSeconds(600),
                now.minusSeconds(60));

        reaper.reapAt(now);

        assertThat(reload(running).getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(reload(waiting).getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
    }

    @Test
    void reapingReleasesTheWalletReservation() {
        var s = scenario();
        Wallet wallet = walletRepository.findByOrgId(s.buyer().getId()).orElseThrow();
        wallet.setReserved(new BigDecimal("200.00"));
        walletRepository.save(wallet);
        Run run = run(s, RunStatus.RUNNING, sandbox(), now.minusSeconds(1), null);
        run.setReservedAmount(new BigDecimal("200.00"));
        runRepository.save(run);

        reaper.reapAt(now);

        assertThat(walletRepository.findByOrgId(s.buyer().getId()).orElseThrow().getReserved())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void aStaleSnapshotDoesNotClobberAnApprovalThatWonTheRace() {
        var s = scenario();
        String sandboxId = sandbox();
        Wallet wallet = walletRepository.findByOrgId(s.buyer().getId()).orElseThrow();
        wallet.setReserved(new BigDecimal("200.00"));
        walletRepository.save(wallet);
        Run run = run(s, RunStatus.AWAITING_APPROVAL, sandboxId, now.plusSeconds(600),
                now.minus(Duration.ofMinutes(31)));
        run.setReservedAmount(new BigDecimal("200.00"));
        runRepository.save(run);

        // The reaper's sweep snapshots this run as AWAITING_APPROVAL with its gate expired...
        RunStatus snapshotStatus = reload(run).getStatus();

        // ...then, before the reaper's terminate call acquires the row lock, the buyer approves it.
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), s.buyer().getId(), UserRole.BUYER_APPROVER));
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = rest.exchange(url("/api/v1/runs/" + run.getId() + "/approve"), HttpMethod.POST,
                new HttpEntity<>(new ApproveRunRequest("approved"), headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // The reaper now acts on its (stale) snapshot; the conditional terminate must be a no-op.
        boolean reaped = runLifecycleService.terminate(run.getId(), snapshotStatus, RunStatus.EXPIRED,
                RunStepKind.TIMEOUT, Map.of("summary", "No approval decision in time; run stopped"));

        assertThat(reaped).isFalse();
        assertThat(reload(run).getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(fakeSandboxProvider.wasKilled(sandboxId)).isFalse();
        assertThat(walletRepository.findByOrgId(s.buyer().getId()).orElseThrow().getReserved())
                .isEqualByComparingTo(new BigDecimal("200.00"));
    }

    @Test
    void approvingAfterTheReaperExpiredTheGateConflicts() {
        var s = scenario();
        Run run = run(s, RunStatus.AWAITING_APPROVAL, sandbox(), now.minusSeconds(1), now.minusSeconds(60));
        reaper.reapAt(now);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), s.buyer().getId(), UserRole.BUYER_APPROVER));
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = rest.exchange(url("/api/v1/runs/" + run.getId() + "/approve"), HttpMethod.POST,
                new HttpEntity<>(new ApproveRunRequest("late"), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reload(run).getStatus()).isEqualTo(RunStatus.EXPIRED);
    }

    String url(String path) {
        return "http://localhost:" + port + path;
    }
}
