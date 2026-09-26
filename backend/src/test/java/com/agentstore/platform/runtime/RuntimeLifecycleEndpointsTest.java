package com.agentstore.platform.runtime;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeLifecycleEndpointsTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    RuntimeFixtures fx;
    @Autowired
    RunRepository runRepository;
    @Autowired
    RunStepRepository stepRepository;
    @Autowired
    FakeSandboxProvider sandbox;

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> post(String path, String token, Object body) {
        return rest.exchange("http://localhost:" + port + path, HttpMethod.POST,
                new HttpEntity<>(body, RuntimeFixtures.bearer(token)), Map.class);
    }

    @Test
    void heartbeatRefreshesLiveness() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        assertThat(post("/runtime/v1/heartbeat", agent.token(), null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getLastHeartbeatAt()).isNotNull();
    }

    @Test
    void logsAreStoredAsAgentReportedSteps() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = post("/runtime/v1/logs", agent.token(), Map.of("entries", List.of(
                Map.of("level", "info", "message", "matched 40 lines", "fields", Map.of("count", 40)),
                Map.of("level", "warn", "message", "one duplicate"))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(stepRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId())).hasSize(2).allSatisfy(s -> {
            assertThat(s.getKind()).isEqualTo(RunStepKind.AGENT_LOG);
            assertThat(s.getSource()).isEqualTo(RunStepSource.AGENT);
        });
    }

    @Test
    void emptyOversizedAndMalformedLogBatchesAreRejectedNotStored() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            tooMany.add(Map.of("level", "info", "message", "m" + i));
        }

        assertThat(post("/runtime/v1/logs", agent.token(), Map.of("entries", List.of())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/runtime/v1/logs", agent.token(), Map.of("entries", tooMany)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/runtime/v1/logs", agent.token(), Map.of("entries", List.of(
                Map.of("level", "info", "message", "x".repeat(4001))))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/runtime/v1/logs", agent.token(), Map.of("entries", List.of(
                Map.of("message", "no level")))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(stepRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId())).isEmpty();
    }

    @Test
    void completeFinishesTheRunAndKillsTheSandbox() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = post("/runtime/v1/complete", agent.token(), Map.of("summary", "reconciled 40 lines"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("COMPLETED");
        assertThat(sandbox.wasKilled(agent.run().getSandboxId())).isTrue();
        assertThat(post("/runtime/v1/heartbeat", agent.token(), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void completeWithoutABodyIsFine() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        assertThat(post("/runtime/v1/complete", agent.token(), null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void completeWhileAGateIsOpenIsAConflict() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        post("/runtime/v1/gates", agent.token(), Map.of("action", "post_journal_entries", "summary", "s", "payload", Map.of()));

        var response = post("/runtime/v1/complete", agent.token(), Map.of("summary", "sneaky"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
    }

    @Test
    void failRecordsTheErrorAndEndsTheRun() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = post("/runtime/v1/fail", agent.token(), Map.of("error", "bank API down", "retriable", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(stepRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId()).get(0).getDetail()).contains("bank API down");
        assertThat(post("/runtime/v1/fail", fx.runningAgent(new BigDecimal("500.00")).token(), Map.of("retriable", false)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);   // error is required
    }
}
