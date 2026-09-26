package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.build.*;
import com.agentstore.platform.build.BuildRunnerClient.RunnerBuild;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.ledger.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunLaunchFromBuildTest extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwtService;
    @Autowired ExecutionFixtures fixtures;
    @Autowired FakeSandboxProvider sandboxes;
    @Autowired FakeBuildRunnerClient runner;
    @Autowired BuildPoller poller;
    @Autowired BuildDispatcher dispatcher;
    @Autowired RunRepository runs;
    @Autowired TemplateBuildRepository builds;
    @Autowired WalletRepository wallets;
    @Autowired RunReaper reaper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("update template_build set status = 'FAILED', failure_code = 'SUPERSEDED' "
                + "where status in ('QUEUED', 'BUILDING')");
        runner.reset();
    }

    RunResponse trigger(ExecutionFixtures.Scenario scenario) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), scenario.buyer().getId(), UserRole.BUYER_ADMIN));
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<RunResponse> response = rest.postForEntity("http://localhost:" + port + "/api/v1/runs",
                new HttpEntity<>(Map.of("connectionId", scenario.connection().getId()), headers), RunResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    BigDecimal reserved(ExecutionFixtures.Scenario scenario) {
        return wallets.findByOrgId(scenario.buyer().getId()).orElseThrow().getReserved();
    }

    @Test
    void aBuiltVersionRunsItsManifestEntrypoint() {
        var scenario = fixtures.liveBuiltTierA(new BigDecimal("100.00"), new BigDecimal("1000.00"), "e2b");
        RunResponse run = trigger(scenario);

        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
        SandboxSpec spec = sandboxes.specForRun(run.id());
        assertThat(spec.command()).isEqualTo(List.of("sh", "-c", "cd /workspace/app && exec python 'agent/main.py'"));
        assertThat(spec.imageRef()).isEqualTo("img-e2b");
    }

    @Test
    void aVersionWithoutARuntimeKeepsTheConfiguredCommand() {
        var scenario = fixtures.liveTierA(new BigDecimal("100.00"), new BigDecimal("1000.00"));
        RunResponse run = trigger(scenario);
        assertThat(sandboxes.specForRun(run.id()).command())
                .isEqualTo(List.of("sh", "-c", "/workspace/app/agentstore-entrypoint"));
    }

    @Test
    void aBackendMismatchQueuesOneRebuildAndTheRunWaits() {
        var scenario = fixtures.liveBuiltTierA(new BigDecimal("100.00"), new BigDecimal("1000.00"), "gvisor");

        RunResponse first = trigger(scenario);
        RunResponse second = trigger(scenario);

        assertThat(first.status()).isEqualTo(RunStatus.PENDING_BUILD);
        assertThat(second.status()).isEqualTo(RunStatus.PENDING_BUILD);
        assertThat(sandboxes.createdSpecs()).noneMatch(s -> s.requestId().equals(first.id().toString()));
        var queued = builds.findByAgentVersionIdAndStatus(scenario.version().getId(), BuildStatus.QUEUED);
        assertThat(queued).hasSize(1);
        assertThat(queued.get(0).getBackend()).isEqualTo("e2b");
        assertThat(queued.get(0).getBundle().getId()).isEqualTo(scenario.version().getBundle().getId());
        assertThat(reserved(scenario)).isEqualByComparingTo("200.00");
    }

    @Test
    void whenTheRebuildSucceedsTheWaitingRunStartsWithAFreshToken() {
        var scenario = fixtures.liveBuiltTierA(new BigDecimal("100.00"), new BigDecimal("1000.00"), "gvisor");
        RunResponse run = trigger(scenario);
        String hashBefore = runs.findById(run.id()).orElseThrow().getCallbackTokenHash();
        TemplateBuild rebuild = builds.findByAgentVersionIdAndStatus(scenario.version().getId(), BuildStatus.QUEUED).get(0);

        assertThat(dispatcher.dispatchOnce()).isTrue();
        runner.answer(rebuild.getId().toString(),
                new RunnerBuild(rebuild.getId().toString(), "SUCCEEDED", "e2b", "img-e2b-new", null, null));
        poller.pollOnce();

        Run started = runs.findById(run.id()).orElseThrow();
        assertThat(started.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(started.getCallbackTokenHash()).isNotEqualTo(hashBefore);
        SandboxSpec spec = sandboxes.specForRun(run.id());
        assertThat(spec.imageRef()).isEqualTo("img-e2b-new");
        assertThat(RunTokenService.hash(spec.envVars().get("AGENT_CALLBACK_TOKEN"))).isEqualTo(started.getCallbackTokenHash());
        assertThat(started.getDeadlineAt()).isAfter(Instant.now().plusSeconds(RunController.SANDBOX_TIMEOUT_SECONDS - 60));
    }

    @Test
    void whenTheRebuildFailsTheWaitingRunFailsAndReleasesItsReservation() {
        var scenario = fixtures.liveBuiltTierA(new BigDecimal("100.00"), new BigDecimal("1000.00"), "gvisor");
        RunResponse run = trigger(scenario);
        TemplateBuild rebuild = builds.findByAgentVersionIdAndStatus(scenario.version().getId(), BuildStatus.QUEUED).get(0);

        dispatcher.dispatchOnce();
        runner.answer(rebuild.getId().toString(),
                new RunnerBuild(rebuild.getId().toString(), "FAILED", "e2b", null, "INSTALL_FAILED", "pip failed"));
        poller.pollOnce();

        assertThat(runs.findById(run.id()).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(reserved(scenario)).isEqualByComparingTo("0.00");
    }

    @Test
    void aRunWaitingPastItsDeadlineTimesOut() {
        var scenario = fixtures.liveBuiltTierA(new BigDecimal("100.00"), new BigDecimal("1000.00"), "gvisor");
        RunResponse run = trigger(scenario);

        reaper.reapAt(Instant.now().plus(RunController.PENDING_BUILD_TIMEOUT).plusSeconds(60));

        assertThat(runs.findById(run.id()).orElseThrow().getStatus()).isEqualTo(RunStatus.TIMED_OUT);
        assertThat(reserved(scenario)).isEqualByComparingTo("0.00");
    }
}
