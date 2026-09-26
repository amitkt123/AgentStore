package com.agentstore.platform.data;

import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.UserRole;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Shared setup for data-plane tests: the data agent scenario, buyer tokens, uploads and pinned runs. */
@Component
public class DataFixtures {

    public static final List<String> GRANTED = List.of("bank_statement", "statement_pdf", "journal_entries", "audit_log");
    public static final String CSV = "date,narration,amount\n2026-01-05,Salary,50000.00\n2026-01-06,Rent,-12000.50\n";
    public static final byte[] PDF = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".getBytes(StandardCharsets.UTF_8);

    public record RunningAgent(ExecutionFixtures.Scenario scenario, Run run, String token) {}

    private final ExecutionFixtures executionFixtures;
    private final RunRepository runRepository;
    private final JwtService jwtService;
    private final DatasetService datasetService;
    private final RunDatasetPinRepository pinRepository;

    public DataFixtures(ExecutionFixtures executionFixtures, RunRepository runRepository, JwtService jwtService,
                        DatasetService datasetService, RunDatasetPinRepository pinRepository) {
        this.executionFixtures = executionFixtures;
        this.runRepository = runRepository;
        this.jwtService = jwtService;
        this.datasetService = datasetService;
        this.pinRepository = pinRepository;
    }

    /** A LIVE agent whose manifest declares typed resources, a buyer with a wallet, and an ACTIVE connection. */
    public ExecutionFixtures.Scenario scenario() {
        return executionFixtures.liveWithManifest("valid-data-agent.json", GRANTED, new BigDecimal("500.00"),
                new BigDecimal("1000.00"));
    }

    public HttpHeaders bearer(Org org, UserRole role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), org.getId(), role));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    public HttpHeaders multipart(Org org, UserRole role) {
        HttpHeaders headers = bearer(org, role);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return headers;
    }

    public static MultiValueMap<String, Object> file(String filename, byte[] content, String displayName) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        body.add("displayName", displayName);
        body.add("sourceLabel", "HDFC export");
        return body;
    }

    /** Uploads through the service (real blobs, real rows), as a buyer with a synthetic user id. */
    public Dataset upload(Org org, String filename, byte[] content) {
        return datasetService.upload(org.getId(), UUID.randomUUID(), filename, new ByteArrayInputStream(content),
                filename, "test");
    }

    public Dataset upload(Org org, String filename, String content) {
        return upload(org, filename, content.getBytes(StandardCharsets.UTF_8));
    }

    /** A RUNNING run of the data agent with a raw token the test uses as the agent would. */
    public RunningAgent runningAgent() {
        return runningAgent(scenario());
    }

    public RunningAgent runningAgent(ExecutionFixtures.Scenario scenario) {
        String token = "rt-" + UUID.randomUUID();
        Run run = executionFixtures.run(scenario, RunStatus.RUNNING, "sbx-" + UUID.randomUUID(), token);
        run.setDeadlineAt(Instant.now().plusSeconds(600));
        return new RunningAgent(scenario, runRepository.save(run), token);
    }

    public void pin(Run run, String resource, Dataset dataset) {
        pinRepository.save(new RunDatasetPin(run.getId(), resource, dataset.getId(), dataset.getSha256()));
    }
}
