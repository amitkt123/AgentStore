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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * A run that joins a rebuild already in flight: {@code queueRebuild} finds it and queues nothing, then the run is
 * saved. If that build finishes in the gap between the two, the build-finished listener finds no waiting run, so the
 * trigger must settle the run itself after saving it. The gap is reproduced deterministically by finishing the build
 * from inside {@code queueRebuild}.
 */
class RunJoinsInFlightRebuildTest extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwtService;
    @Autowired ExecutionFixtures fixtures;
    @Autowired FakeSandboxProvider sandboxes;
    @Autowired FakeBuildRunnerClient runner;
    @Autowired BuildPoller poller;
    @Autowired RunRepository runs;
    @Autowired TemplateBuildRepository builds;
    @Autowired WalletRepository wallets;
    @Autowired JdbcTemplate jdbc;
    @SpyBean RebuildService rebuildService;

    @BeforeEach
    void reset() {
        jdbc.update("update template_build set status = 'FAILED', failure_code = 'SUPERSEDED' "
                + "where status in ('QUEUED', 'BUILDING')");
        runner.reset();
    }

    /** A rebuild for the current bundle that a previous trigger queued and the runner is already building. */
    TemplateBuild inFlightRebuild(ExecutionFixtures.Scenario scenario) {
        TemplateBuild build = new TemplateBuild(scenario.version(), scenario.version().getBundle(), "e2b",
                "python-3.12", "agent/main.py");
        build.setStatus(BuildStatus.BUILDING);
        build.setStartedAt(Instant.now());
        build.setAttempts(1);
        return builds.save(build);
    }

    /** The build finishes while the trigger is between queueRebuild and saving its run. */
    void finishDuringQueueRebuild(RunnerBuild result) {
        doAnswer(invocation -> {
            runner.answer(result.buildId(), result);
            poller.pollOnce();
            return null;   // queued nothing: the in-flight build was joined
        }).when(rebuildService).queueRebuild(any());
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

    @Test
    void aBuildThatSucceedsInTheGapStartsTheRun() {
        var scenario = fixtures.liveBuiltTierA(new BigDecimal("100.00"), new BigDecimal("1000.00"), "gvisor");
        TemplateBuild rebuild = inFlightRebuild(scenario);
        finishDuringQueueRebuild(new RunnerBuild(rebuild.getId().toString(), "SUCCEEDED", "e2b", "img-e2b-new", null, null));

        RunResponse run = trigger(scenario);

        Run started = runs.findById(run.id()).orElseThrow();
        assertThat(started.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(sandboxes.specForRun(run.id()).imageRef()).isEqualTo("img-e2b-new");
    }

    @Test
    void aBuildThatFailsInTheGapFailsTheRunAndReleasesItsReservation() {
        var scenario = fixtures.liveBuiltTierA(new BigDecimal("100.00"), new BigDecimal("1000.00"), "gvisor");
        TemplateBuild rebuild = inFlightRebuild(scenario);
        finishDuringQueueRebuild(new RunnerBuild(rebuild.getId().toString(), "FAILED", "e2b", null, "INSTALL_FAILED", "pip failed"));

        RunResponse run = trigger(scenario);

        assertThat(runs.findById(run.id()).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(wallets.findByOrgId(scenario.buyer().getId()).orElseThrow().getReserved()).isEqualByComparingTo("0.00");
    }
}
