package com.agentstore.platform.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunFlowService;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.execution.RunStepKind;
import com.agentstore.platform.execution.RunStepRepository;
import com.agentstore.platform.execution.RunStepSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class NetworkEventsTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    RuntimeFixtures fx;
    @Autowired
    RunStepRepository steps;
    @Autowired
    RunRepository runs;
    @Autowired
    RunFlowService flowService;

    private ResponseEntity<String> post(String token, Object body) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.setBearerAuth(token);
        }
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + "/runtime/v1/network-events", HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
    }

    private Map<String, Object> event(String host, int count) {
        return Map.of("host", host, "count", count);
    }

    @Test
    void recordsOneAgentSourcedStepPerEvent() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        var response = post(agent.token(), Map.of("events", List.of(event("evil.example", 3),
                Map.of("host", "evil.example", "count", 2, "callsite", "main.py:9"))));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        var recorded = steps.findByRunIdOrderByCreatedAtAsc(agent.run().getId()).stream()
                .filter(s -> s.getKind() == RunStepKind.NETWORK_BLOCKED).toList();
        assertThat(recorded).hasSize(2);
        assertThat(recorded).allMatch(s -> s.getSource() == RunStepSource.AGENT && s.getBlock() == null);
    }

    @Test
    void invalidBatchesAreRejected() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        String t = agent.token();
        assertThat(post(t, Map.of("events", List.of())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(t, Map.of("events", List.of(event("a.example", 0)))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(t, Map.of("events", List.of(event("a.example", -3)))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(t, Map.of("events", List.of(event("h".repeat(256), 1)))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(t, Map.of("events", List.of(Map.of("count", 1)))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var tooMany = java.util.stream.IntStream.range(0, 101).mapToObj(i -> event("h" + i + ".example", 1)).toList();
        assertThat(post(t, Map.of("events", tooMany)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void terminalRunIs409AndBadTokenIs401() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        Run run = agent.run();
        run.setStatus(RunStatus.COMPLETED);
        runs.save(run);
        Map<String, Object> body = Map.of("events", List.of(event("a.example", 1)));
        assertThat(post(agent.token(), body).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(post("wrong", body).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post(null, body).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void flowSumsBlockedAttempts() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        post(agent.token(), Map.of("events", List.of(event("evil.example", 3), event("evil.example", 2), event("other.example", 1))));

        Run run = runs.findById(agent.run().getId()).orElseThrow();
        assertThat(flowService.compute(run).blockedAttempts()).isEqualTo(6);
    }
}
