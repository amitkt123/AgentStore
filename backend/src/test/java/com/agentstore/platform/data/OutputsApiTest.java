package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.runtime.RunPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OutputsApiTest extends AbstractStorageIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    DataFixtures fx;
    @Autowired
    RunOutputWriter writer;
    @Autowired
    RunOutputRepository outputRepository;
    @Autowired
    com.agentstore.platform.execution.RunRepository runRepository;
    @Autowired
    ManifestParser parser;

    /** Insertion-ordered: the CSV header follows key order, and Map.of's order is not stable. */
    static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }

    RunOutput write(DataFixtures.RunningAgent agent, String resource, boolean approval, List<Map<String, Object>> rows) {
        var principal = RunPrincipal.forRun(runRepository.findById(agent.run().getId()).orElseThrow(), parser);
        writer.write(principal, resource, rows, approval);
        List<RunOutput> all = outputRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId());
        return all.get(all.size() - 1);
    }

    @SuppressWarnings("rawtypes")
    org.springframework.http.ResponseEntity<Map> call(String path, com.agentstore.platform.identity.Org org, UserRole role) {
        return rest.exchange("http://localhost:" + port + path, HttpMethod.GET,
                new HttpEntity<>(null, fx.bearer(org, role)), Map.class);
    }

    @Test
    void listsOutputsWithStatusAndAnOptionalPreview() {
        var agent = fx.runningAgent();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            rows.add(Map.of("event", "e" + i));
        }
        write(agent, "audit_log", false, rows);
        write(agent, "journal_entries", true, List.of(Map.of("bank_line", "l1", "amount", 1)));

        var list = rest.exchange("http://localhost:" + port + "/api/v1/runs/" + agent.run().getId() + "/outputs?preview=50",
                HttpMethod.GET, new HttpEntity<>(null, fx.bearer(agent.scenario().buyer(), UserRole.BUYER_MEMBER)), List.class);

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).hasSize(2);
        Map<?, ?> audit = (Map<?, ?>) list.getBody().get(0);
        assertThat(audit.get("resource")).isEqualTo("audit_log");
        assertThat(audit.get("status")).isEqualTo("COMMITTED");
        assertThat(audit.get("recordCount")).isEqualTo(60);
        assertThat((List<?>) audit.get("rows")).hasSize(50);
        assertThat(((Map<?, ?>) list.getBody().get(1)).get("status")).isEqualTo("STAGED");

        var noPreview = rest.exchange("http://localhost:" + port + "/api/v1/runs/" + agent.run().getId() + "/outputs",
                HttpMethod.GET, new HttpEntity<>(null, fx.bearer(agent.scenario().buyer(), UserRole.BUYER_ADMIN)), List.class);
        assertThat(((Map<?, ?>) noPreview.getBody().get(0)).containsKey("rows")).isFalse();
    }

    @Test
    void onlyTheRunsBuyerOrgMaySeeThem() {
        var agent = fx.runningAgent();
        var other = fx.scenario();
        write(agent, "audit_log", false, List.of(Map.of("event", "e")));

        var response = call("/api/v1/runs/" + agent.run().getId() + "/outputs", other.buyer(), UserRole.BUYER_ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aCommittedOutputDownloadsAsCsvWithFormulasNeutralised() {
        var agent = fx.runningAgent();
        RunOutput output = write(agent, "audit_log", false,
                List.of(row("event", "=cmd|' /C calc'!A0", "amount", -5), row("event", "ok", "extra", "a,b")));

        var response = rest.exchange("http://localhost:" + port + "/api/v1/runs/" + agent.run().getId() + "/outputs/"
                        + output.getId() + "/download?format=csv", HttpMethod.GET,
                new HttpEntity<>(null, fx.bearer(agent.scenario().buyer(), UserRole.BUYER_MEMBER)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("attachment").contains("audit_log");
        assertThat(response.getBody()).startsWith("event,amount,extra\r\n").contains("'=cmd|").contains(",-5,")
                .contains("ok,,\"a,b\"");
    }

    @Test
    void aCommittedOutputDownloadsAsAJsonArray() {
        var agent = fx.runningAgent();
        RunOutput output = write(agent, "audit_log", false, List.of(row("event", "a"), row("event", "b")));

        var response = rest.exchange("http://localhost:" + port + "/api/v1/runs/" + agent.run().getId() + "/outputs/"
                        + output.getId() + "/download?format=json", HttpMethod.GET,
                new HttpEntity<>(null, fx.bearer(agent.scenario().buyer(), UserRole.BUYER_ADMIN)), String.class);

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isEqualTo("[{\"event\":\"a\"},{\"event\":\"b\"}]");
    }

    @Test
    void stagedDiscardedAndUnknownOutputsCannotBeDownloadedAndBadFormatsAreRejected() {
        var agent = fx.runningAgent();
        RunOutput staged = write(agent, "journal_entries", true, List.of(Map.of("bank_line", "l", "amount", 1)));
        RunOutput committed = write(agent, "audit_log", false, List.of(Map.of("event", "a")));
        String base = "/api/v1/runs/" + agent.run().getId() + "/outputs/";
        var org = agent.scenario().buyer();

        var notCommitted = call(base + staged.getId() + "/download", org, UserRole.BUYER_ADMIN);
        staged.setStatus(OutputStatus.DISCARDED);
        outputRepository.save(staged);
        var discarded = call(base + staged.getId() + "/download", org, UserRole.BUYER_ADMIN);
        var badFormat = call(base + committed.getId() + "/download?format=xlsx", org, UserRole.BUYER_ADMIN);
        var unknown = call(base + java.util.UUID.randomUUID() + "/download", org, UserRole.BUYER_ADMIN);

        assertThat(notCommitted.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(notCommitted.getBody().get("code")).isEqualTo("OUTPUT_NOT_COMMITTED");
        assertThat(discarded.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(badFormat.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badFormat.getBody().get("code")).isEqualTo("INVALID_FORMAT");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
