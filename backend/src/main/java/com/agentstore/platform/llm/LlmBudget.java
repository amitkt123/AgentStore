package com.agentstore.platform.llm;

import com.agentstore.platform.execution.RunRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * A run's LLM token budget. Reserving and settling are single atomic statements in their own short transactions; no
 * database transaction is held across a provider call.
 */
@Component
public class LlmBudget {

    private final RunRepository runs;

    public LlmBudget(RunRepository runs) {
        this.runs = runs;
    }

    /** True when {@code tokens} were reserved; false when they do not fit {@code maxTokensPerRun} (or are not positive). */
    public boolean reserve(UUID runId, long tokens, long maxTokensPerRun) {
        if (tokens < 1 || tokens > maxTokensPerRun) {
            return false;
        }
        return runs.reserveLlmTokens(runId, Math.toIntExact(tokens), maxTokensPerRun) == 1;
    }

    /** Turns a reservation into actual usage. The actual count may exceed the reservation. */
    public void settle(UUID runId, long reserved, long usedTokens) {
        int used = (int) Math.min(Integer.MAX_VALUE, Math.max(0, usedTokens));
        runs.settleLlmTokens(runId, Math.toIntExact(reserved), used);
    }

    /** Gives a reservation back; {@code used} does not change. */
    public void release(UUID runId, long reserved) {
        settle(runId, reserved, 0);
    }
}
