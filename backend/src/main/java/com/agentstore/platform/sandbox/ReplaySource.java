package com.agentstore.platform.sandbox;

import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.llm.LlmCallRepository;
import com.agentstore.platform.llm.LlmCallStatus;
import com.agentstore.platform.llm.LlmSource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The ownership check WS3 deferred to WS6 (spec §3.1 step 5), shared by every place that accepts a
 * {@code replayRunId}: starting a run and saving a profile with {@code llmMode = REPLAY}. A source must be a SANDBOX
 * run of the same agent, owned by the same org, with at least one recorded OK call — otherwise the id could tell an
 * attacker whether a run exists, or whose it is, one check at a time.
 */
@Component
public class ReplaySource {

    private final RunRepository runs;
    private final LlmCallRepository llmCalls;

    public ReplaySource(RunRepository runs, LlmCallRepository llmCalls) {
        this.runs = runs;
        this.llmCalls = llmCalls;
    }

    /** Throws 403/422 {@code REPLAY_SOURCE_INVALID} unless {@code replayRunId} is a valid, recorded source. */
    public void check(UUID orgId, UUID agentId, UUID replayRunId) {
        Run source = replayRunId == null ? null : runs.findById(replayRunId).orElse(null);
        boolean validOwner = source != null && source.getConnection() == null && source.getMode() == RunMode.SANDBOX
                && orgId.equals(source.getInitiatedByOrgId()) && agentId.equals(source.getAgentVersion().getAgent().getId());
        if (!validOwner) {
            throw new SandboxApiException(HttpStatus.FORBIDDEN, "REPLAY_SOURCE_INVALID",
                    "The replay source is not a sandbox run of this agent owned by your org");
        }
        boolean hasRecording = llmCalls.existsByRunIdAndStatusAndSourceNotAndRecordingExpiresAtIsNotNullAndRecordingPurgedAtIsNull(
                replayRunId, LlmCallStatus.OK, LlmSource.MOCK);
        if (!hasRecording) {
            throw new SandboxApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REPLAY_SOURCE_INVALID",
                    "That run has no recorded calls to replay");
        }
    }
}
