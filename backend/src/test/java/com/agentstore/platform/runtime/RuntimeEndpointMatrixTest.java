package com.agentstore.platform.runtime;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Every runtime endpoint: bad token, terminal run, past deadline. (Not-granted cases live with each endpoint.) */
class RuntimeEndpointMatrixTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    RuntimeFixtures fx;
    @Autowired
    RunRepository runRepository;

    static Stream<Arguments> endpoints() {
        UUID gate = UUID.randomUUID();
        return Stream.of(
                Arguments.of(HttpMethod.GET, "/runtime/v1/context", null),
                Arguments.of(HttpMethod.GET, "/runtime/v1/resources/bank_statement", null),
                Arguments.of(HttpMethod.GET, "/runtime/v1/resources/bank_statement/records", null),
                Arguments.of(HttpMethod.GET, "/runtime/v1/resources/bank_statement/file", null),
                Arguments.of(HttpMethod.POST, "/runtime/v1/resources/journal_entries/records", Map.of("records", List.of(Map.of("a", 1)))),
                Arguments.of(HttpMethod.POST, "/runtime/v1/gates", Map.of("action", "post_journal_entries", "summary", "s", "payload", Map.of())),
                Arguments.of(HttpMethod.GET, "/runtime/v1/gates/" + gate, null),
                Arguments.of(HttpMethod.POST, "/runtime/v1/llm/chat", Map.of("messages", List.of())),
                Arguments.of(HttpMethod.POST, "/runtime/v1/usage", Map.of("quantity", 1, "unit", "reconciled_transaction")),
                Arguments.of(HttpMethod.POST, "/runtime/v1/logs", Map.of("entries", List.of(Map.of("level", "info", "message", "m")))),
                Arguments.of(HttpMethod.POST, "/runtime/v1/heartbeat", null),
                Arguments.of(HttpMethod.POST, "/runtime/v1/complete", Map.of("summary", "s")),
                Arguments.of(HttpMethod.POST, "/runtime/v1/fail", Map.of("error", "e", "retriable", false)));
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> call(HttpMethod method, String path, String token, Object body) {
        return rest.exchange("http://localhost:" + port + path, method,
                new HttpEntity<>(body, RuntimeFixtures.bearer(token)), Map.class);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("endpoints")
    void aBadTokenIsUnauthorized(HttpMethod method, String path, Object body) {
        assertThat(call(method, path, "not-a-token", body).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("endpoints")
    void aTerminalRunIsRejectedWithRunTerminated(HttpMethod method, String path, Object body) {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        var run = agent.run();
        run.setStatus(RunStatus.CANCELLED);
        runRepository.save(run);

        var response = call(method, path, agent.token(), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("RUN_TERMINATED");
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("endpoints")
    void aRunPastItsDeadlineIsRejectedWithRunTerminated(HttpMethod method, String path, Object body) {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        var run = agent.run();
        run.setDeadlineAt(Instant.now().minusSeconds(1));
        runRepository.save(run);

        var response = call(method, path, agent.token(), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("RUN_TERMINATED");
    }
}
