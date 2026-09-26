package com.agentstore.platform.runtime;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeAuthTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    RuntimeFixtures fx;
    @Autowired
    RunRepository runRepository;
    @Autowired
    JwtService jwtService;

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> get(String path, HttpHeaders headers) {
        return rest.exchange("http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    @Test
    void aValidRunTokenGetsTheContext() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = get("/runtime/v1/context", RuntimeFixtures.bearer(agent.token()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body.get("runId")).isEqualTo(agent.run().getId().toString());
        assertThat(body.get("mode")).isEqualTo("PRODUCTION");
        assertThat(body.get("input")).isEqualTo(Map.of());
        List<Object> resources = ((List<?>) body.get("grantedScopes")).stream()
                .<Object>map(s -> ((Map<?, ?>) s).get("resource")).toList();
        assertThat(resources).containsExactlyInAnyOrder("bank_statement", "journal_entries");   // "invoices" is declared but not granted
        Map<?, ?> limits = (Map<?, ?>) body.get("limits");
        assertThat(new BigDecimal(String.valueOf(limits.get("costCapRemaining")))).isEqualByComparingTo("500.00");
        assertThat(limits.get("currency")).isEqualTo("INR");
    }

    @Test
    void missingWrongAndNonRunTokensAreUnauthorizedProblems() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        HttpHeaders jwt = new HttpHeaders();
        jwt.setBearerAuth(jwtService.issue(UUID.randomUUID(), agent.scenario().buyer().getId(), UserRole.BUYER_ADMIN));

        for (HttpHeaders headers : List.of(new HttpHeaders(), RuntimeFixtures.bearer("not-a-token"), jwt)) {
            var response = get("/runtime/v1/context", headers);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
            assertThat(response.getBody().get("code")).isEqualTo("UNAUTHORIZED");
        }
    }

    @Test
    void aTerminalRunsTokenStopsWorking() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        var run = agent.run();
        run.setStatus(RunStatus.COMPLETED);
        runRepository.save(run);

        var response = get("/runtime/v1/context", RuntimeFixtures.bearer(agent.token()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("RUN_TERMINATED");
    }

    @Test
    void aRunPastItsDeadlineIsRejected() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        var run = agent.run();
        run.setDeadlineAt(Instant.now().minusSeconds(5));
        runRepository.save(run);

        var response = get("/runtime/v1/context", RuntimeFixtures.bearer(agent.token()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("RUN_TERMINATED");
    }

    @Test
    void anUnsupportedApiVersionInTheUserAgentIsRejectedClearly() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        HttpHeaders headers = RuntimeFixtures.bearer(agent.token());

        headers.set(HttpHeaders.USER_AGENT, "agentstore-python/0.1 runtime-api/2");
        var v2 = get("/runtime/v1/context", headers);
        assertThat(v2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(v2.getBody().get("code")).isEqualTo("UNSUPPORTED_API_VERSION");
        assertThat((String) v2.getBody().get("detail")).contains("runtime-api/1");

        headers.set(HttpHeaders.USER_AGENT, "agentstore-python/0.1 runtime-api/1");
        assertThat(get("/runtime/v1/context", headers).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theRunTokenDoesNotOpenTheBuyerApi() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = get("/api/v1/runs/mine", RuntimeFixtures.bearer(agent.token()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
