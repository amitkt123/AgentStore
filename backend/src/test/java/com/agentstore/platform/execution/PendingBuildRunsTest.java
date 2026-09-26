package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.build.*;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.User;
import com.agentstore.platform.identity.UserRepository;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PendingBuildRuns trusts the version's real state, not the build-finished event alone. */
class PendingBuildRunsTest extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwtService;
    @Autowired ExecutionFixtures fixtures;
    @Autowired FakeSandboxProvider sandboxes;
    @Autowired FakeBuildRunnerClient runner;
    @Autowired BuildDispatcher dispatcher;
    @Autowired PendingBuildRuns pendingBuildRuns;
    @Autowired RunRepository runs;
    @Autowired TemplateBuildRepository builds;
    @Autowired AgentBundleRepository bundles;
    @Autowired UserRepository users;
    @Autowired WalletRepository wallets;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("update template_build set status = 'FAILED', failure_code = 'SUPERSEDED' "
                + "where status in ('QUEUED', 'BUILDING')");
        runner.reset();
    }

    ResponseEntity<RunResponse> trigger(ExecutionFixtures.Scenario scenario) {
        return trigger(scenario, RunResponse.class);
    }

    <T> ResponseEntity<T> trigger(ExecutionFixtures.Scenario scenario, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), scenario.buyer().getId(), UserRole.BUYER_ADMIN));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("http://localhost:" + port + "/api/v1/runs",
                new HttpEntity<>(Map.of("connectionId", scenario.connection().getId()), headers), responseType);
    }

    RunStatus statusOf(RunResponse run) {
        return runs.findById(run.id()).orElseThrow().getStatus();
    }

    /** A finished build of a bundle the version no longer has. */
    TemplateBuild staleBuild(ExecutionFixtures.Scenario scenario, BuildStatus status) {
        User user = users.save(new User(null, "old-" + UUID.randomUUID() + "@example.com", null, "Old"));
        UUID bundleId = UUID.randomUUID();
        AgentBundle old = bundles.save(new AgentBundle(bundleId, scenario.version().getAgent(), user.getId(),
                "bundles/" + scenario.version().getAgent().getId() + "/" + bundleId + ".zip", "d".repeat(64), 100,
                BundleStatus.UPLOADED));
        TemplateBuild build = new TemplateBuild(scenario.version(), old, "e2b", "python-3.12", "agent/main.py");
        build.setStatus(status);
        build.setImageRef(status == BuildStatus.SUCCEEDED ? "img-stale" : null);
        return builds.save(build);
    }

    @Test
    void aStaleSuccessDoesNotLaunchRunsOnAnImageTheVersionLacks() {
        var scenario = fixtures.liveBuiltTierA(new BigDecimal("100.00"), new BigDecimal("1000.00"), "gvisor");
        RunResponse run = trigger(scenario).getBody();
        TemplateBuild stale = staleBuild(scenario, BuildStatus.SUCCEEDED);

        pendingBuildRuns.onBuildFinished(new BuildFinishedEvent(stale.getId(), scenario.version().getId(), "e2b", true, null));

        assertThat(statusOf(run)).isEqualTo(RunStatus.PENDING_BUILD);
        assertThat(sandboxes.createdSpecs()).noneMatch(s -> s.requestId().equals(run.id().toString()));
    }

    @Test
    void aStaleFailureDoesNotFailRunsStillWaitingOnAHealthyBuild() {
        var scenario = fixtures.liveBuiltTierA(new BigDecimal("100.00"), new BigDecimal("1000.00"), "gvisor");
        RunResponse run = trigger(scenario).getBody();
        TemplateBuild stale = staleBuild(scenario, BuildStatus.FAILED);

        pendingBuildRuns.onBuildFinished(
                new BuildFinishedEvent(stale.getId(), scenario.version().getId(), "e2b", false, "TIMEOUT"));

        assertThat(statusOf(run)).isEqualTo(RunStatus.PENDING_BUILD);
        assertThat(wallets.findByOrgId(scenario.buyer().getId()).orElseThrow().getReserved()).isEqualByComparingTo("100.00");
    }

    @Test
    void aFailureWhileAnotherBuildOfTheBundleIsQueuedLeavesTheRunWaiting() {
        var scenario = fixtures.liveBuiltTierA(new BigDecimal("100.00"), new BigDecimal("1000.00"), "gvisor");
        RunResponse run = trigger(scenario).getBody();
        TemplateBuild first = builds.findByAgentVersionIdAndStatus(scenario.version().getId(), BuildStatus.QUEUED).get(0);
        TemplateBuild second = builds.save(new TemplateBuild(scenario.version(), scenario.version().getBundle(), "e2b",
                "python-3.12", "agent/main.py"));
        first.setStatus(BuildStatus.FAILED);
        builds.save(first);

        pendingBuildRuns.onBuildFinished(
                new BuildFinishedEvent(first.getId(), scenario.version().getId(), "e2b", false, "TIMEOUT"));
        assertThat(statusOf(run)).isEqualTo(RunStatus.PENDING_BUILD);

        second.setStatus(BuildStatus.FAILED);
        builds.save(second);
        pendingBuildRuns.onBuildFinished(
                new BuildFinishedEvent(second.getId(), scenario.version().getId(), "e2b", false, "TIMEOUT"));
        assertThat(statusOf(run)).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void aRunningRebuildIsNotQueuedAgain() {
        var scenario = fixtures.liveBuiltTierA(new BigDecimal("100.00"), new BigDecimal("1000.00"), "gvisor");
        trigger(scenario);
        assertThat(dispatcher.dispatchOnce()).isTrue();

        RunResponse second = trigger(scenario).getBody();

        assertThat(second.status()).isEqualTo(RunStatus.PENDING_BUILD);
        assertThat(builds.findByAgentVersionIdAndStatus(scenario.version().getId(), BuildStatus.QUEUED)).isEmpty();
        assertThat(builds.findByAgentVersionIdAndStatus(scenario.version().getId(), BuildStatus.BUILDING)).hasSize(1);
    }

    @Test
    void aVersionWithoutAnImageNeverLaunchesARun() {
        var scenario = fixtures.liveTierA(new BigDecimal("100.00"), new BigDecimal("1000.00"));
        jdbc.update("update agent_version set image_ref = null where id = ?", scenario.version().getId());

        ResponseEntity<String> response = trigger(scenario, String.class);

        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
        Run run = runs.findByConnectionBuyerOrgIdOrderByStartedAtDesc(scenario.buyer().getId()).get(0);
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(sandboxes.createdSpecs()).noneMatch(s -> s.requestId().equals(run.getId().toString()));
        assertThat(wallets.findByOrgId(scenario.buyer().getId()).orElseThrow().getReserved()).isEqualByComparingTo("0.00");
    }
}
