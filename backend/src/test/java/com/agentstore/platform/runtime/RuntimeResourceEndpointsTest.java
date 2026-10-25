package com.agentstore.platform.runtime;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.execution.RunStepKind;
import com.agentstore.platform.execution.RunStepRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeResourceEndpointsTest extends AbstractStorageIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    RuntimeFixtures fx;
    @Autowired
    RunStepRepository stepRepository;

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> call(HttpMethod method, String path, String token, Object body) {
        return rest.exchange("http://localhost:" + port + path, method,
                new HttpEntity<>(body, RuntimeFixtures.bearer(token)), Map.class);
    }

    private long violationSteps(java.util.UUID runId) {
        return stepRepository.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .filter(s -> s.getKind() == RunStepKind.SCOPE_VIOLATION).count();
    }

    @Test
    void aGrantedReadOfAnUnboundResourceIsNotFoundAndNotAViolation() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = call(HttpMethod.GET, "/runtime/v1/resources/bank_statement/records", agent.token(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("RESOURCE_NOT_BOUND");
        assertThat(violationSteps(agent.run().getId())).isZero();
    }

    @Test
    void aWriteStepRecordsWhenTheCallStartedAndAgentLogsDoNot() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        call(HttpMethod.POST, "/runtime/v1/resources/journal_entries/records", agent.token(),
                Map.of("records", List.of(Map.of("account", "1000"))));
        call(HttpMethod.POST, "/runtime/v1/logs", agent.token(),
                Map.of("entries", List.of(Map.of("level", "info", "message", "hi"))));
        var steps = stepRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId());
        var staged = steps.stream().filter(s -> s.getKind() == RunStepKind.WRITE_STAGED).findFirst().orElseThrow();
        assertThat(staged.getStartedAt()).isNotNull().isBeforeOrEqualTo(staged.getCreatedAt());
        var log = steps.stream().filter(s -> s.getKind() == RunStepKind.AGENT_LOG).findFirst().orElseThrow();
        assertThat(log.getStartedAt()).isNull();
    }

    @Test
    void aGrantedWriteIsAcceptedAndStagedBecauseTheScopeRequiresApproval() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = call(HttpMethod.POST, "/runtime/v1/resources/journal_entries/records", agent.token(),
                Map.of("records", List.of(Map.of("account", "1000"))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("accepted")).isEqualTo(1);
        assertThat(response.getBody().get("staged")).isEqualTo(true);
        assertThat(violationSteps(agent.run().getId())).isZero();
    }

    @Test
    void undeclaredUngrantedAndWrongTypeAccessIsForbiddenAndRecorded() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var undeclared = call(HttpMethod.GET, "/runtime/v1/resources/payroll/records", agent.token(), null);
        var ungranted = call(HttpMethod.GET, "/runtime/v1/resources/invoices", agent.token(), null);
        var wrongType = call(HttpMethod.POST, "/runtime/v1/resources/bank_statement/records", agent.token(),
                Map.of("records", List.of(Map.of("a", 1))));

        for (var r : List.of(undeclared, ungranted, wrongType)) {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(r.getBody().get("code")).isEqualTo("SCOPE_NOT_GRANTED");
        }
        assertThat(violationSteps(agent.run().getId())).isEqualTo(3);
    }

    @Test
    void caseVariantsAndEncodedSlashesAreNeverGranted() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var caseVariant = call(HttpMethod.GET, "/runtime/v1/resources/Bank_Statement", agent.token(), null);
        var encodedSlash = call(HttpMethod.GET, "/runtime/v1/resources/..%2Fsecret", agent.token(), null);

        assertThat(caseVariant.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(encodedSlash.getStatusCode().is4xxClientError()).isTrue();   // the firewall or the guard, never a 5xx or a 2xx
    }

    @Test
    void emptyOrMissingWriteBodiesAreValidationErrorsNotServerErrors() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var empty = call(HttpMethod.POST, "/runtime/v1/resources/journal_entries/records", agent.token(), Map.of("records", List.of()));
        var missing = call(HttpMethod.POST, "/runtime/v1/resources/journal_entries/records", agent.token(), null);

        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(empty.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void badPagingParametersAreClampedOrRejected() {
        assertThat(RuntimeResourceController.clampLimit(-5)).isEqualTo(1);
        assertThat(RuntimeResourceController.clampLimit(0)).isEqualTo(1);
        assertThat(RuntimeResourceController.clampLimit(50)).isEqualTo(50);
        assertThat(RuntimeResourceController.clampLimit(5_000_000)).isEqualTo(1000);

        var agent = fx.runningAgent(new BigDecimal("500.00"));
        var response = call(HttpMethod.GET, "/runtime/v1/resources/bank_statement/records?limit=abc", agent.token(), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void anAgentWithoutAnLlmBlockCannotUseTheLlmEndpoint() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = call(HttpMethod.POST, "/runtime/v1/llm/chat", agent.token(),
                Map.of("messages", List.of(Map.of("role", "user", "content", "hi"))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("LLM_NOT_DECLARED");
    }
}
