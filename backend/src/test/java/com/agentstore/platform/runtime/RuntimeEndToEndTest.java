package com.agentstore.platform.runtime;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.*;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.runtime.InMemoryBackends.InMemoryResourceBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The WS1 acceptance test: a scripted agent that only ever speaks /runtime/v1 reads a dataset, asks for approval, is
 * approved by the buyer, writes, reports usage and completes; the trace is entirely platform-recorded.
 */
@Import(InMemoryBackends.class)
class RuntimeEndToEndTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    RuntimeFixtures fx;
    @Autowired
    InMemoryResourceBackend resources;
    @Autowired
    JwtService jwtService;
    @Autowired
    RunRepository runRepository;
    @Autowired
    RunStepRepository stepRepository;
    @Autowired
    FakeSandboxProvider sandbox;

    @BeforeEach
    void reset() {
        resources.written.clear();   // the backend bean is shared across tests in the cached context
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> agentCall(HttpMethod method, String path, String token, Object body) {
        return rest.exchange(url(path), method, new HttpEntity<>(body, RuntimeFixtures.bearer(token)), Map.class);
    }

    @Test
    void aScriptedAgentReadsGatesWritesChargesAndCompletesWithAPlatformRecordedTrace() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        resources.seed("bank_statement", List.of(Map.of("id", 1, "amount", 100), Map.of("id", 2, "amount", 250)));
        String token = agent.token();

        assertThat(agentCall(HttpMethod.GET, "/runtime/v1/context", token, null).getStatusCode()).isEqualTo(HttpStatus.OK);

        var page = agentCall(HttpMethod.GET, "/runtime/v1/resources/bank_statement/records?limit=10", token, null);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) page.getBody().get("records")).hasSize(2);

        var gate = agentCall(HttpMethod.POST, "/runtime/v1/gates", token, Map.of(
                "action", "post_journal_entries", "summary", "Post 2 entries", "payload", Map.of("rows", 2),
                "affectsResources", List.of("journal_entries")));
        assertThat(gate.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String gateId = (String) gate.getBody().get("gateId");
        assertThat(agentCall(HttpMethod.GET, "/runtime/v1/gates/" + gateId, token, null).getBody().get("status")).isEqualTo("PENDING");

        HttpHeaders buyer = new HttpHeaders();
        buyer.setBearerAuth(jwtService.issue(UUID.randomUUID(), agent.scenario().buyer().getId(), UserRole.BUYER_MEMBER));
        buyer.setContentType(MediaType.APPLICATION_JSON);
        var decision = rest.exchange(url("/api/v1/runs/" + agent.run().getId() + "/gates/" + gateId + "/decision"),
                HttpMethod.POST, new HttpEntity<>(Map.of("decision", "APPROVE", "note", "ok"), buyer), RunResponse.class);
        assertThat(decision.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(agentCall(HttpMethod.GET, "/runtime/v1/gates/" + gateId + "?wait=5", token, null).getBody().get("status"))
                .isEqualTo("APPROVED");

        var write = agentCall(HttpMethod.POST, "/runtime/v1/resources/journal_entries/records", token,
                Map.of("records", List.of(Map.of("account", "1000"), Map.of("account", "2000"))));
        assertThat(write.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(write.getBody().get("staged")).isEqualTo(true);   // the scope requiresApproval

        assertThat(agentCall(HttpMethod.POST, "/runtime/v1/usage", token,
                Map.of("quantity", 2, "unit", "reconciled_transaction")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(agentCall(HttpMethod.POST, "/runtime/v1/complete", token, Map.of("summary", "done")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        Run run = runRepository.findById(agent.run().getId()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.getTotalCharged()).isEqualByComparingTo("4.00");
        assertThat(sandbox.wasKilled(run.getSandboxId())).isTrue();
        var steps = stepRepository.findByRunIdOrderByCreatedAtAsc(run.getId());
        assertThat(steps).extracting(RunStep::getKind).containsExactly(
                RunStepKind.DATA_READ, RunStepKind.GATE_REQUESTED, RunStepKind.GATE_DECIDED,
                RunStepKind.WRITE_STAGED, RunStepKind.USAGE, RunStepKind.COMPLETE);
        assertThat(steps).allMatch(s -> s.getSource() == RunStepSource.PLATFORM);   // nothing here is the agent's own word
        assertThat(resources.written).hasSize(2);
    }

    @Test
    void anUndeclaredReadIsDeniedAndVisibleInTheTrace() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = agentCall(HttpMethod.GET, "/runtime/v1/resources/payroll/records", agent.token(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("SCOPE_NOT_GRANTED");
        var steps = stepRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId());
        assertThat(steps).singleElement().satisfies(s -> {
            assertThat(s.getKind()).isEqualTo(RunStepKind.SCOPE_VIOLATION);
            assertThat(s.getSource()).isEqualTo(RunStepSource.PLATFORM);
            assertThat(s.getDetail()).contains("payroll");
        });
    }

    @Test
    void threeUndeclaredReadsStopTheRun() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        for (int i = 0; i < 3; i++) {
            agentCall(HttpMethod.GET, "/runtime/v1/resources/payroll/records", agent.token(), null);
        }

        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(agentCall(HttpMethod.GET, "/runtime/v1/context", agent.token(), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
