package com.agentstore.platform.runtime;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.*;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.runtime.InMemoryBackends.InMemoryResourceBackend;
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

@Import(InMemoryBackends.class)
class RunStepBlockPersistenceTest extends AbstractIntegrationTest {

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
    RunStepRepository stepRepository;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> agentCall(HttpMethod method, String path, String token, Object body) {
        return rest.exchange(url(path), method, new HttpEntity<>(body, RuntimeFixtures.bearer(token)), Map.class);
    }

    @Test
    void everyRecordingCallSiteSetsTheExpectedBlock() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        resources.seed("bank_statement", List.of(Map.of("id", 1, "amount", 100)));
        String token = agent.token();

        agentCall(HttpMethod.GET, "/runtime/v1/resources/bank_statement/records?limit=10", token, null);

        var gate = agentCall(HttpMethod.POST, "/runtime/v1/gates", token, Map.of(
                "action", "post_journal_entries", "summary", "Post 1 entry", "payload", Map.of("rows", 1),
                "affectsResources", List.of("journal_entries")));
        String gateId = (String) gate.getBody().get("gateId");

        HttpHeaders buyer = new HttpHeaders();
        buyer.setBearerAuth(jwtService.issue(UUID.randomUUID(), agent.scenario().buyer().getId(), UserRole.BUYER_APPROVER));
        buyer.setContentType(MediaType.APPLICATION_JSON);
        rest.exchange(url("/api/v1/runs/" + agent.run().getId() + "/gates/" + gateId + "/decision"),
                HttpMethod.POST, new HttpEntity<>(Map.of("decision", "APPROVE", "note", "ok"), buyer), RunResponse.class);

        agentCall(HttpMethod.POST, "/runtime/v1/resources/journal_entries/records", token,
                Map.of("records", List.of(Map.of("account", "1000"))));
        agentCall(HttpMethod.POST, "/runtime/v1/usage", token, Map.of("quantity", 1, "unit", "reconciled_transaction"));
        agentCall(HttpMethod.POST, "/runtime/v1/complete", token, Map.of("summary", "done"));

        var steps = stepRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId());
        assertThat(steps).extracting(RunStep::getKind, RunStep::getBlock).containsExactly(
                org.assertj.core.groups.Tuple.tuple(RunStepKind.DATA_READ, "read:bank_statement"),
                org.assertj.core.groups.Tuple.tuple(RunStepKind.GATE_REQUESTED, "gate:post_journal_entries"),
                org.assertj.core.groups.Tuple.tuple(RunStepKind.GATE_DECIDED, "gate:post_journal_entries"),
                org.assertj.core.groups.Tuple.tuple(RunStepKind.WRITE_STAGED, "draft:journal_entries"),
                org.assertj.core.groups.Tuple.tuple(RunStepKind.USAGE, "charge"),
                org.assertj.core.groups.Tuple.tuple(RunStepKind.COMPLETE, null));
    }

    @Test
    void anUndeclaredReadsBlockMirrorsTheAttemptedAccess() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        agentCall(HttpMethod.GET, "/runtime/v1/resources/payroll/records", agent.token(), null);

        var steps = stepRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId());
        assertThat(steps).singleElement().satisfies(s -> {
            assertThat(s.getKind()).isEqualTo(RunStepKind.SCOPE_VIOLATION);
            assertThat(s.getBlock()).isEqualTo("read:payroll");
        });
    }
}
