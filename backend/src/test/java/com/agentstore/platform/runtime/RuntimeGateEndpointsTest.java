package com.agentstore.platform.runtime;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.*;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeGateEndpointsTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    RuntimeFixtures fx;
    @Autowired
    JwtService jwtService;
    @Autowired
    RunRepository runRepository;
    @Autowired
    GateRequestRepository gateRepository;
    @Autowired
    RunStepRepository stepRepository;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> call(HttpMethod method, String path, String token, Object body) {
        return rest.exchange(url(path), method, new HttpEntity<>(body, RuntimeFixtures.bearer(token)), Map.class);
    }

    private Map<String, Object> gateBody() {
        return Map.of("action", "post_journal_entries", "summary", "Post 2 entries", "payload", Map.of("rows", 2),
                "affectsResources", List.of("journal_entries"));
    }

    private void buyerDecides(RuntimeFixtures.RunningAgent agent, String gateId, String decision) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), agent.scenario().buyer().getId(), UserRole.BUYER_MEMBER));
        headers.setContentType(MediaType.APPLICATION_JSON);
        rest.exchange(url("/api/v1/runs/" + agent.run().getId() + "/gates/" + gateId + "/decision"), HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", decision), headers), RunResponse.class);
    }

    @Test
    void aDeclaredGatePausesTheRunAndReturnsItsIdAndExpiry() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = call(HttpMethod.POST, "/runtime/v1/gates", agent.token(), gateBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("PENDING");
        assertThat(response.getBody().get("expiresAt")).isNotNull();
        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(stepRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId())).extracting(RunStep::getKind)
                .containsExactly(RunStepKind.GATE_REQUESTED);
    }

    @Test
    void anUndeclaredGateActionIsForbiddenAndRecorded() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = call(HttpMethod.POST, "/runtime/v1/gates", agent.token(),
                Map.of("action", "wire_money", "summary", "s", "payload", Map.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("SCOPE_NOT_GRANTED");
        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getViolationCount()).isEqualTo(1);
    }

    @Test
    void aSecondOpenGateIsAConflictAndBadBodiesAreValidationErrors() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        call(HttpMethod.POST, "/runtime/v1/gates", agent.token(), gateBody());

        assertThat(call(HttpMethod.POST, "/runtime/v1/gates", agent.token(), gateBody()).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        var other = fx.runningAgent(new BigDecimal("500.00"));
        var missing = call(HttpMethod.POST, "/runtime/v1/gates", other.token(), Map.of("action", "post_journal_entries"));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void pollingShowsPendingThenApproved() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        String gateId = (String) call(HttpMethod.POST, "/runtime/v1/gates", agent.token(), gateBody()).getBody().get("gateId");

        assertThat(call(HttpMethod.GET, "/runtime/v1/gates/" + gateId, agent.token(), null).getBody().get("status"))
                .isEqualTo("PENDING");
        buyerDecides(agent, gateId, "APPROVE");

        var approved = call(HttpMethod.GET, "/runtime/v1/gates/" + gateId, agent.token(), null);
        assertThat(approved.getBody().get("status")).isEqualTo("APPROVED");
        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void aLongPollReturnsAsSoonAsTheBuyerDecides() throws Exception {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        String gateId = (String) call(HttpMethod.POST, "/runtime/v1/gates", agent.token(), gateBody()).getBody().get("gateId");

        Thread buyer = new Thread(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
            buyerDecides(agent, gateId, "APPROVE");
        });
        buyer.start();
        long started = System.nanoTime();
        var response = call(HttpMethod.GET, "/runtime/v1/gates/" + gateId + "?wait=20", agent.token(), null);
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        buyer.join();

        assertThat(response.getBody().get("status")).isEqualTo("APPROVED");
        assertThat(took).isGreaterThan(Duration.ofMillis(1000)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void waitValuesAreClampedAndGarbageIsRejected() {
        assertThat(RuntimeGateController.clampWait(-5)).isZero();
        assertThat(RuntimeGateController.clampWait(0)).isZero();
        assertThat(RuntimeGateController.clampWait(12)).isEqualTo(12);
        assertThat(RuntimeGateController.clampWait(99_999)).isEqualTo(30);

        var agent = fx.runningAgent(new BigDecimal("500.00"));
        String gateId = (String) call(HttpMethod.POST, "/runtime/v1/gates", agent.token(), gateBody()).getBody().get("gateId");
        long started = System.nanoTime();
        var negative = call(HttpMethod.GET, "/runtime/v1/gates/" + gateId + "?wait=-5", agent.token(), null);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(negative.getBody().get("status")).isEqualTo("PENDING");

        var garbage = call(HttpMethod.GET, "/runtime/v1/gates/" + gateId + "?wait=abc", agent.token(), null);
        assertThat(garbage.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(garbage.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void aGateOfAnotherRunIsNotFound() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        var other = fx.runningAgent(new BigDecimal("500.00"));
        String gateId = (String) call(HttpMethod.POST, "/runtime/v1/gates", other.token(), gateBody()).getBody().get("gateId");

        var response = call(HttpMethod.GET, "/runtime/v1/gates/" + gateId, agent.token(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/runtime/v1/gates/not-a-uuid", agent.token(), null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectedGateEndsTheRunAndTheTokenStopsWorking() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        String gateId = (String) call(HttpMethod.POST, "/runtime/v1/gates", agent.token(), gateBody()).getBody().get("gateId");

        buyerDecides(agent, gateId, "REJECT");

        assertThat(gateRepository.findById(UUID.fromString(gateId)).orElseThrow().getStatus()).isEqualTo(GateStatus.REJECTED);
        var after = call(HttpMethod.GET, "/runtime/v1/gates/" + gateId, agent.token(), null);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(after.getBody().get("code")).isEqualTo("RUN_TERMINATED");
    }
}
