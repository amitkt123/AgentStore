package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GateDecisionEndpointTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    GateService gateService;
    @Autowired
    GateRequestRepository gateRepository;
    @Autowired
    RunRepository runRepository;

    record Fx(ExecutionFixtures.Scenario scenario, Run run, GateRequest gate) {}

    private Fx awaiting(String token) {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(scenario, RunStatus.RUNNING, "sbx-" + token, token);
        GateRequest gate = gateService.open(run.getId(), "post_journal_entries", 5, "Post entries", "{}", "[]");
        return new Fx(scenario, run, gate);
    }

    private HttpHeaders approver(UUID orgId, UUID userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(userId, orgId, UserRole.BUYER_MEMBER));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<RunResponse> decide(Fx fx, HttpHeaders headers, Object body) {
        return rest.exchange(url("/api/v1/runs/" + fx.run().getId() + "/gates/" + fx.gate().getId() + "/decision"),
                HttpMethod.POST, new HttpEntity<>(body, headers), RunResponse.class);
    }

    /** For error cases: the body is an error document, not a RunResponse. */
    private ResponseEntity<String> decideRaw(Fx fx, HttpHeaders headers, Object body) {
        return rest.exchange(url("/api/v1/runs/" + fx.run().getId() + "/gates/" + fx.gate().getId() + "/decision"),
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void approvingThroughTheGateEndpointRecordsWhoDecided() {
        Fx fx = awaiting("tok-gd-1");
        UUID userId = UUID.randomUUID();

        var response = decide(fx, approver(fx.scenario().buyer().getId(), userId), Map.of("decision", "APPROVE", "note", "ok"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(RunStatus.RUNNING);
        GateRequest gate = gateRepository.findById(fx.gate().getId()).orElseThrow();
        assertThat(gate.getStatus()).isEqualTo(GateStatus.APPROVED);
        assertThat(gate.getDecidedByUserId()).isEqualTo(userId);
        assertThat(gate.getDecisionNote()).isEqualTo("ok");
    }

    @Test
    void aBuyerAdminCanApproveThroughTheGateEndpoint() {
        Fx fx = awaiting("tok-gd-admin");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), fx.scenario().buyer().getId(), UserRole.BUYER_ADMIN));
        headers.setContentType(MediaType.APPLICATION_JSON);

        var response = decide(fx, headers, Map.of("decision", "APPROVE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(gateRepository.findById(fx.gate().getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.APPROVED);
    }

    @Test
    void rejectingThroughTheGateEndpointCancelsTheRun() {
        Fx fx = awaiting("tok-gd-2");

        var response = decide(fx, approver(fx.scenario().buyer().getId(), UUID.randomUUID()), Map.of("decision", "REJECT"));

        assertThat(response.getBody().status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(gateRepository.findById(fx.gate().getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.REJECTED);
    }

    @Test
    void anApproverFromAnotherOrgIsForbiddenAndNothingChanges() {
        Fx fx = awaiting("tok-gd-3");

        var response = decideRaw(fx, approver(UUID.randomUUID(), UUID.randomUUID()), Map.of("decision", "APPROVE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(gateRepository.findById(fx.gate().getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.PENDING);
    }

    @Test
    void anUnknownDecisionValueIsABadRequest() {
        Fx fx = awaiting("tok-gd-4");

        var response = decideRaw(fx, approver(fx.scenario().buyer().getId(), UUID.randomUUID()), Map.of("decision", "MAYBE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void theOldApproveAndRejectEndpointsDelegateToTheSinglePendingGate() {
        Fx approve = awaiting("tok-gd-5");
        rest.exchange(url("/api/v1/runs/" + approve.run().getId() + "/approve"), HttpMethod.POST,
                new HttpEntity<>(new ApproveRunRequest("fine"), approver(approve.scenario().buyer().getId(), UUID.randomUUID())),
                RunResponse.class);
        assertThat(gateRepository.findById(approve.gate().getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.APPROVED);

        Fx reject = awaiting("tok-gd-6");
        rest.exchange(url("/api/v1/runs/" + reject.run().getId() + "/reject"), HttpMethod.POST,
                new HttpEntity<>(new ApproveRunRequest("no"), approver(reject.scenario().buyer().getId(), UUID.randomUUID())),
                RunResponse.class);
        assertThat(gateRepository.findById(reject.gate().getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.REJECTED);
        assertThat(runRepository.findById(reject.run().getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.CANCELLED);
    }
}
