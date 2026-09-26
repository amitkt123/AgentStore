package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A RUNNING run of the runtime test agent, with a raw token the test uses as the agent would. */
@Component
public class RuntimeFixtures {

    public static final String RESOURCE_READ = "bank_statement";
    public static final String RESOURCE_WRITE = "journal_entries";
    /** Declared in the manifest as READ, but the buyer did not grant it. */
    public static final String RESOURCE_UNGRANTED = "invoices";

    public record RunningAgent(ExecutionFixtures.Scenario scenario, Run run, String token) {}

    private final ExecutionFixtures fixtures;
    private final RunRepository runRepository;

    public RuntimeFixtures(ExecutionFixtures fixtures, RunRepository runRepository) {
        this.fixtures = fixtures;
        this.runRepository = runRepository;
    }

    public RunningAgent runningAgent(BigDecimal spendCap) {
        var scenario = fixtures.liveWithManifest("valid-runtime-agent.json", List.of(RESOURCE_READ, RESOURCE_WRITE),
                spendCap, new BigDecimal("1000.00"));
        String token = "rt-" + UUID.randomUUID();
        Run run = fixtures.run(scenario, RunStatus.RUNNING, "sbx-" + UUID.randomUUID(), token);
        run.setDeadlineAt(Instant.now().plusSeconds(600));
        return new RunningAgent(scenario, runRepository.save(run), token);
    }

    public static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
