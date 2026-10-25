package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunResponse;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.execution.RunTokenService;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.runtime.RuntimeFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataPlaneEndToEndTest extends AbstractStorageIntegrationTest {

    static Map<String, Object> entry(String bankLine, int amount) {
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("bank_line", bankLine);
        entry.put("amount", amount);
        return entry;
    }

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    DataFixtures fx;
    @Autowired
    RunRepository runRepository;
    @Autowired
    RunDatasetPinRepository pinRepository;
    @Autowired
    BindingResolver resolver;
    @Autowired
    ManifestParser parser;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings("rawtypes")
    private org.springframework.http.ResponseEntity<Map> call(HttpMethod method, String path, org.springframework.http.HttpHeaders headers, Object body) {
        return rest.exchange(url(path), method, new HttpEntity<>(body, headers), Map.class);
    }

    @Test
    void aBuyerUploadsBindsRunsAnAgentApprovesAndDownloadsWhatWasCommitted() {
        var s = fx.scenario();
        var buyer = fx.bearer(s.buyer(), UserRole.BUYER_ADMIN);
        var approver = fx.bearer(s.buyer(), UserRole.BUYER_MEMBER);

        // 1. upload a statement CSV and a PDF through the API
        var csv = rest.exchange(url("/api/v1/datasets"), HttpMethod.POST, new HttpEntity<>(
                DataFixtures.file("jan.csv", DataFixtures.CSV.getBytes(), "HDFC Jan"), fx.multipart(s.buyer(), UserRole.BUYER_ADMIN)), Map.class);
        var pdf = rest.exchange(url("/api/v1/datasets"), HttpMethod.POST, new HttpEntity<>(
                DataFixtures.file("jan.pdf", DataFixtures.PDF, "HDFC Jan PDF"), fx.multipart(s.buyer(), UserRole.BUYER_ADMIN)), Map.class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        String connection = s.connection().getId().toString();

        // 2. the trigger is blocked until every readable resource is bound
        assertThat(call(HttpMethod.POST, "/api/v1/runs", buyer, Map.of("connectionId", connection)).getBody().get("code"))
                .isEqualTo("MISSING_BINDING");

        // 3. bind both files
        assertThat(call(HttpMethod.PUT, "/api/v1/connections/" + connection + "/bindings/bank_statement", buyer,
                Map.of("datasetId", csv.getBody().get("id"))).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.PUT, "/api/v1/connections/" + connection + "/bindings/statement_pdf", buyer,
                Map.of("datasetId", pdf.getBody().get("id"))).getStatusCode()).isEqualTo(HttpStatus.OK);

        // 4. trigger; give the run a token we know (the launcher only stores its hash)
        var triggered = rest.exchange(url("/api/v1/runs"), HttpMethod.POST,
                new HttpEntity<>(Map.of("connectionId", connection), buyer), RunResponse.class);
        assertThat(triggered.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID runId = triggered.getBody().id();
        String token = "rt-" + UUID.randomUUID();
        runRepository.updateCallbackTokenHash(runId, RunTokenService.hash(token));
        Run run = runRepository.findById(runId).orElseThrow();
        run.setStatus(RunStatus.RUNNING);
        run.setDeadlineAt(Instant.now().plusSeconds(600));
        runRepository.save(run);
        assertThat(pinRepository.findByIdRunId(runId)).hasSize(2);
        var agent = RuntimeFixtures.bearer(token);

        // 5. the agent reads through /runtime/v1 only
        assertThat(call(HttpMethod.GET, "/runtime/v1/resources/bank_statement", agent, null).getBody().get("rowCount")).isEqualTo(2);
        var page = call(HttpMethod.GET, "/runtime/v1/resources/bank_statement/records", agent, null);
        assertThat((List<?>) page.getBody().get("records")).hasSize(2);
        var link = call(HttpMethod.GET, "/runtime/v1/resources/statement_pdf/file", agent, null);
        assertThat((String) link.getBody().get("url")).endsWith("/file/content");

        // 6. it stages a write and asks for approval covering it (insertion-ordered maps: the CSV header follows key
        //    order; whole-number amounts: a JSON write is parsed with the platform's mapper, which reads 12.50 as 12.5)
        var write = call(HttpMethod.POST, "/runtime/v1/resources/journal_entries/records", agent, Map.of("records",
                List.of(entry("Salary", 50000), entry("Rent", -12000))));
        assertThat(write.getBody().get("staged")).isEqualTo(true);
        var gate = call(HttpMethod.POST, "/runtime/v1/gates", agent, Map.of("action", "post_journal_entries",
                "summary", "Post 2 entries", "payload", Map.of("rows", 2), "affectsResources", List.of("journal_entries")));
        String gateId = (String) gate.getBody().get("gateId");

        // 7. before approval nothing is downloadable; then the buyer approves
        var outputsBefore = rest.exchange(url("/api/v1/runs/" + runId + "/outputs?preview=50"), HttpMethod.GET,
                new HttpEntity<>(null, approver), List.class);
        Map<?, ?> staged = (Map<?, ?>) outputsBefore.getBody().get(0);
        assertThat(staged.get("status")).isEqualTo("STAGED");
        assertThat((List<?>) staged.get("rows")).hasSize(2);
        assertThat(call(HttpMethod.GET, "/api/v1/runs/" + runId + "/outputs/" + staged.get("id") + "/download", approver, null)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.POST, "/api/v1/runs/" + runId + "/gates/" + gateId + "/decision", approver,
                Map.of("decision", "APPROVE", "note", "looks right")).getStatusCode()).isEqualTo(HttpStatus.OK);

        // 8. the output is committed and downloads as CSV
        var outputsAfter = rest.exchange(url("/api/v1/runs/" + runId + "/outputs"), HttpMethod.GET,
                new HttpEntity<>(null, approver), List.class);
        Map<?, ?> committed = (Map<?, ?>) outputsAfter.getBody().get(0);
        assertThat(committed.get("status")).isEqualTo("COMMITTED");
        var download = rest.exchange(url("/api/v1/runs/" + runId + "/outputs/" + committed.get("id") + "/download?format=csv"),
                HttpMethod.GET, new HttpEntity<>(null, approver), String.class);
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(download.getBody()).isEqualTo("bank_line,amount\r\nSalary,50000\r\nRent,-12000\r\n");

        // 9. a SANDBOX run can never read buyer data, by the resolver or by the backend
        assertThatThrownBy(() -> resolver.resolve(s.connection(), parser.parse(s.version().getManifestJson()), RunMode.SANDBOX))
                .isInstanceOf(DataApiException.class);
        var sandbox = fx.runningAgent(s);
        Run sandboxRun = sandbox.run();
        sandboxRun.setMode(RunMode.SANDBOX);
        runRepository.save(sandboxRun);
        pinRepository.save(new RunDatasetPin(sandboxRun.getId(), "bank_statement", UUID.fromString((String) csv.getBody().get("id")),
                (String) csv.getBody().get("sha256")));
        var denied = call(HttpMethod.GET, "/runtime/v1/resources/bank_statement/records", RuntimeFixtures.bearer(sandbox.token()), null);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().get("code")).isEqualTo("DATA_MODE_MISMATCH");
    }
}
