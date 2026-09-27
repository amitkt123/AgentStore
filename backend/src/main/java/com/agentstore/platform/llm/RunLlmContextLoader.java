package com.agentstore.platform.llm;

import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.runtime.RuntimeApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Reads the run's LLM settings in one short read-only transaction (open-in-view is off). */
@Component
public class RunLlmContextLoader {

    private final RunRepository runs;

    public RunLlmContextLoader(RunRepository runs) {
        this.runs = runs;
    }

    @Transactional(readOnly = true)
    public RunLlmContext load(UUID runId) {
        Run run = runs.findById(runId)
                .orElseThrow(() -> new RuntimeApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Run not found"));
        return new RunLlmContext(run.getAgentVersion().getAgent().getDeveloperOrg().getId(), run.getLlmMode(),
                run.getReplayRunId(), run.getLlmMockJson());
    }
}
