package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.runtime.RuntimeFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tomcat stops swallowing an unread request body after 2 MB and then closes the connection, which would let the 413
 * for a 6 MB body race the client's write. Unlimited swallowing keeps these tests deterministic; a real client that
 * loses the race sees a reset or the 413, and either way nothing is stored.
 */
@TestPropertySource(properties = "server.tomcat.max-swallow-size=-1")
class RuntimeWriteTest extends AbstractStorageIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    DataFixtures fx;
    @Autowired
    RunOutputRepository outputRepository;

    @SuppressWarnings("rawtypes")
    org.springframework.http.ResponseEntity<Map> write(String token, String resource, List<Map<String, Object>> records) {
        return rest.exchange("http://localhost:" + port + "/runtime/v1/resources/" + resource + "/records",
                HttpMethod.POST, new HttpEntity<>(Map.of("records", records), RuntimeFixtures.bearer(token)), Map.class);
    }

    static Map<String, Object> entry(String bankLine, Object amount) {
        return Map.of("bank_line", bankLine, "amount", amount);
    }

    @Test
    void anApprovalScopeWriteIsStagedAsAJsonlBlobWithARow() throws Exception {
        var agent = fx.runningAgent();

        var response = write(agent.token(), "journal_entries", List.of(entry("l1", 10), entry("l2", "12.50")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("accepted")).isEqualTo(2);
        assertThat(response.getBody().get("staged")).isEqualTo(true);
        List<RunOutput> outputs = outputRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId());
        assertThat(outputs).hasSize(1);
        RunOutput output = outputs.get(0);
        assertThat(output.getStatus()).isEqualTo(OutputStatus.STAGED);
        assertThat(output.getRecordCount()).isEqualTo(2);
        assertThat(output.getResourceName()).isEqualTo("journal_entries");
        assertThat(output.getStorageKey()).isEqualTo("outputs/" + agent.run().getId() + "/" + output.getId() + ".jsonl");
        try (InputStream in = storage.open(output.getStorageKey())) {
            String[] lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n");
            assertThat(lines).hasSize(2);
            assertThat(lines[0]).contains("\"bank_line\":\"l1\"");
        }
        assertThat(storage.size(output.getStorageKey())).contains(output.getSizeBytes());
    }

    @Test
    void aScopeWithoutApprovalCommitsImmediately() {
        var agent = fx.runningAgent();

        var response = write(agent.token(), "audit_log", List.of(Map.of("event", "reconciled")));

        assertThat(response.getBody().get("staged")).isEqualTo(false);
        assertThat(outputRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId()))
                .extracting(RunOutput::getStatus).containsExactly(OutputStatus.COMMITTED);
    }

    @Test
    void aWriteThatFailsTheSchemaIs422WithRowErrorsAndStoresNothing() {
        var agent = fx.runningAgent();

        var missingKey = write(agent.token(), "journal_entries", List.of(Map.of("amount", 5)));
        var badType = write(agent.token(), "journal_entries", List.of(entry("l1", 1), entry("l2", "abc")));

        assertThat(missingKey.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(missingKey.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
        Map<?, ?> first = (Map<?, ?>) ((List<?>) missingKey.getBody().get("errors")).get(0);
        assertThat(first.get("row")).isEqualTo(1);
        assertThat(first.get("column")).isEqualTo("bank_line");
        Map<?, ?> second = (Map<?, ?>) ((List<?>) badType.getBody().get("errors")).get(0);
        assertThat(second.get("row")).isEqualTo(2);
        assertThat(second.get("column")).isEqualTo("amount");
        assertThat(outputRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId())).isEmpty();
    }

    @Test
    void onlyTheFirstFiftyRowErrorsComeBack() {
        var agent = fx.runningAgent();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            rows.add(entry("l" + i, "abc"));
        }

        var response = write(agent.token(), "journal_entries", rows);

        assertThat((List<?>) response.getBody().get("errors")).hasSize(50);
    }

    @Test
    void aBodyOverFiveMegabytesIs413BeforeItIsParsed() {
        var agent = fx.runningAgent();
        String big = "x".repeat(6 * 1024 * 1024);

        var response = write(agent.token(), "audit_log", List.of(Map.of("blob", big)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().get("code")).isEqualTo("WRITE_TOO_LARGE");
        assertThat(outputRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId())).isEmpty();
    }

    @Test
    void aChunkedBodyOverFiveMegabytesIsAlso413() throws Exception {
        var agent = fx.runningAgent();
        byte[] prefix = "{\"records\":[{\"blob\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}]}".getBytes(StandardCharsets.UTF_8);
        int filler = 6 * 1024 * 1024;
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/runtime/v1/resources/audit_log/records"))
                .header("Authorization", "Bearer " + agent.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.SequenceInputStream(
                        java.util.Collections.enumeration(List.of(
                                new java.io.ByteArrayInputStream(prefix),
                                new java.io.InputStream() {
                                    int left = filler;

                                    @Override
                                    public int read() {
                                        return left-- > 0 ? 'x' : -1;
                                    }
                                },
                                new java.io.ByteArrayInputStream(suffix))))))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("WRITE_TOO_LARGE");
    }

    @Test
    void theRunTotalIsCappedAt200Megabytes() {
        var agent = fx.runningAgent();
        outputRepository.save(new RunOutput(java.util.UUID.randomUUID(), agent.run().getId(), "audit_log",
                OutputStatus.COMMITTED, 1, 200L * 1024 * 1024 - 10, "outputs/preloaded"));

        var response = write(agent.token(), "audit_log", List.of(Map.of("event", "this pushes it over")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().get("code")).isEqualTo("WRITE_TOO_LARGE");
    }

    @Test
    void aWriteToAPdfOrUndeclaredResourceIsStillAScopeViolation() {
        var agent = fx.runningAgent();

        assertThat(write(agent.token(), "statement_pdf", List.of(Map.of("a", 1))).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(write(agent.token(), "payroll", List.of(Map.of("a", 1))).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(outputRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId())).isEmpty();
    }
}
