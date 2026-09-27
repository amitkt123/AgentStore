package com.agentstore.platform.llm;

import com.agentstore.platform.execution.RunStepKind;
import com.agentstore.platform.runtime.RunPrincipal;
import com.agentstore.platform.runtime.RunStepRecorder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Writes the {@code llm_call} row and the run's {@code LLM_CALL} step for every call the gateway metered. */
@Component
public class LlmCallLog {

    private final LlmCallRepository calls;
    private final RunStepRecorder steps;

    public LlmCallLog(LlmCallRepository calls, RunStepRecorder steps) {
        this.calls = calls;
        this.steps = steps;
    }

    /**
     * Saves and flushes the row first (a unique-index conflict on a replay ordinal surfaces here, before any step is
     * written), then records the step. {@code run_step} has no block column until WS1b, so the block rides in the detail.
     */
    public void record(RunPrincipal principal, LlmCall call) {
        calls.saveAndFlush(call);
        steps.record(principal, RunStepKind.LLM_CALL, detail(call));
    }

    static Map<String, Object> detail(LlmCall call) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("block", "llm");
        detail.put("calls", 1);
        detail.put("tokens", call.getInputTokens() + call.getOutputTokens());
        detail.put("inputTokens", call.getInputTokens());
        detail.put("outputTokens", call.getOutputTokens());
        detail.put("model", call.getModelId());
        detail.put("source", call.getSource().name());
        detail.put("status", call.getStatus().name());
        if (call.getErrorCode() != null) {
            detail.put("errorCode", call.getErrorCode());
        }
        return detail;
    }
}
