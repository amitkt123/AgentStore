package com.agentstore.platform.sandbox;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.execution.FakeSandboxProvider;
import com.agentstore.platform.execution.GateRequestRepository;
import com.agentstore.platform.execution.GateStatus;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.execution.RunStep;
import com.agentstore.platform.execution.RunStepKind;
import com.agentstore.platform.execution.RunStepRepository;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.runtime.RuntimeFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Gate auto-policy (spec §3.3) and the developer's own gate decision endpoint. */
class SandboxGateTest extends AbstractStorageIntegrationTest {

    static final String ACTION = "post_journal_entries";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired SandboxFixtures fx;
    @Autowired SandboxRunService service;
    @Autowired SandboxProfileRepository profiles;
    @Autowired FakeSandboxProvider sandbox;
    @Autowired RunRepository runs;
    @Autowired GateRequestRepository gates;
    @Autowired RunStepRepository steps;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    Run startRun(SandboxFixtures.Dev dev, SandboxProfile profile) {
        return service.start(dev.org().getId(), UUID.randomUUID(), dev.version().getId(), profile.getId(), null);
    }

    String tokenOf(Run run) {
        return sandbox.specForRun(run.getId()).envVars().get("AGENTSTORE_RUN_TOKEN");
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> openGate(Run run) {
        var headers = com.agentstore.platform.runtime.RuntimeFixtures.bearer(tokenOf(run));
        return rest.exchange(url("/runtime/v1/gates"), HttpMethod.POST, new HttpEntity<>(
                Map.of("action", ACTION, "summary", "Post 2 entries", "payload", Map.of("rows", 2),
                        "affectsResources", List.of("journal_entries")), headers), Map.class);
    }

    @Test
    void autoApproveByDefaultLetsTheRunKeepGoing() {
        var dev = fx.developer("valid-runtime-agent.json");
        SandboxProfile profile = fx.profile(dev, "auto-approve");
        profile.setGatePolicy(new GatePolicy(GatePolicy.GateMode.AUTO_APPROVE, Map.of()).toJson());
        profiles.save(profile);
        Run run = startRun(dev, profile);

        var response = openGate(run);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("APPROVED");
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);
        var gate = gates.findFirstByRunIdAndStatusOrderByCreatedAtAsc(run.getId(), GateStatus.APPROVED).orElseThrow();
        assertThat(gate.getDecisionNote()).isEqualTo("auto policy");
        assertThat(gate.getDecidedByUserId()).isNull();
        assertThat(steps.findByRunIdOrderByCreatedAtAsc(run.getId())).extracting(RunStep::getKind)
                .contains(RunStepKind.GATE_DECIDED);
    }

    @Test
    void autoRejectStopsTheRunAndFurtherCallsAre409() {
        var dev = fx.developer("valid-runtime-agent.json");
        SandboxProfile profile = fx.profile(dev, "auto-reject");
        profile.setGatePolicy(new GatePolicy(GatePolicy.GateMode.AUTO_REJECT, Map.of()).toJson());
        profiles.save(profile);
        Run run = startRun(dev, profile);

        var response = openGate(run);

        assertThat(response.getBody().get("status")).isEqualTo("REJECTED");
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.CANCELLED);
        var usage = rest.exchange(url("/runtime/v1/usage"), HttpMethod.POST, new HttpEntity<>(
                Map.of("quantity", 1, "unit", "reconciled_transaction"), com.agentstore.platform.runtime.RuntimeFixtures.bearer(tokenOf(run))),
                Map.class);
        assertThat(usage.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aPerActionOverrideWinsOverTheDefault() {
        var dev = fx.developer("valid-runtime-agent.json");
        fx.verify(dev.org());   // two runs stay open at once here; only the policy override is under test
        SandboxProfile rejectsByDefaultButApprovesThisAction = fx.profile(dev, "override-a");
        rejectsByDefaultButApprovesThisAction.setGatePolicy(new GatePolicy(GatePolicy.GateMode.AUTO_REJECT,
                Map.of(ACTION, GatePolicy.GateMode.AUTO_APPROVE)).toJson());
        profiles.save(rejectsByDefaultButApprovesThisAction);
        Run approved = startRun(dev, rejectsByDefaultButApprovesThisAction);
        assertThat(openGate(approved).getBody().get("status")).isEqualTo("APPROVED");

        SandboxProfile approvesByDefaultButRejectsThisAction = fx.profile(dev, "override-b");
        approvesByDefaultButRejectsThisAction.setGatePolicy(new GatePolicy(GatePolicy.GateMode.AUTO_APPROVE,
                Map.of(ACTION, GatePolicy.GateMode.AUTO_REJECT)).toJson());
        profiles.save(approvesByDefaultButRejectsThisAction);
        Run rejected = startRun(dev, approvesByDefaultButRejectsThisAction);
        assertThat(openGate(rejected).getBody().get("status")).isEqualTo("REJECTED");
    }

    @Test
    void manualWaitsForTheOwnersDecisionAndRefusesOthers() {
        var dev = fx.developer("valid-runtime-agent.json");
        var other = fx.developer("valid-runtime-agent.json");
        SandboxProfile profile = fx.profile(dev, "manual");   // default MANUAL
        Run run = startRun(dev, profile);

        var opened = openGate(run);
        assertThat(opened.getBody().get("status")).isEqualTo("PENDING");
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        UUID gateId = UUID.fromString((String) opened.getBody().get("gateId"));

        var detail = rest.exchange(url("/api/v1/sandbox/runs/" + run.getId()), HttpMethod.GET,
                new HttpEntity<>(null, fx.bearer(dev.org(), UserRole.DEVELOPER)), Map.class);
        assertThat(((Map<?, ?>) detail.getBody().get("pendingGate")).get("id")).isEqualTo(gateId.toString());

        var foreignAttempt = rest.exchange(url("/api/v1/sandbox/runs/" + run.getId() + "/gates/" + gateId + "/decision"),
                HttpMethod.POST, new HttpEntity<>(Map.of("approve", true), fx.bearer(other.org(), UserRole.DEVELOPER)), Map.class);
        assertThat(foreignAttempt.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        var decided = rest.exchange(url("/api/v1/sandbox/runs/" + run.getId() + "/gates/" + gateId + "/decision"),
                HttpMethod.POST, new HttpEntity<>(Map.of("approve", true, "note", "ok"), fx.bearer(dev.org(), UserRole.DEVELOPER)), Map.class);
        assertThat(decided.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void aPendingGateExpiresWithTheRunLikeAnyOtherRun() {
        var dev = fx.developer("valid-runtime-agent.json");
        SandboxProfile profile = fx.profile(dev, "expiring");
        Run run = startRun(dev, profile);
        openGate(run);
        var gate = gates.findFirstByRunIdAndStatusOrderByCreatedAtAsc(run.getId(), GateStatus.PENDING).orElseThrow();

        reaper.reapAt(gate.getExpiresAt().plusSeconds(1));

        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.EXPIRED);
    }

    @Autowired com.agentstore.platform.execution.RunReaper reaper;
}
