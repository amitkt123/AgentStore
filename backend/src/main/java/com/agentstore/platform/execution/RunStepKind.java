package com.agentstore.platform.execution;

/**
 * READ..FAILED are the legacy kinds an agent reports itself over the old callback. TIMEOUT and COST_CAP_REACHED are
 * recorded by the platform only. The rest are recorded by the platform from /runtime/v1 calls; AGENT_LOG carries what
 * the agent said and is labelled as agent-reported.
 */
public enum RunStepKind {
    READ, WRITE, GATE, COST, COMPLETE, FAILED, TIMEOUT, COST_CAP_REACHED,
    DATA_READ, DATA_WRITE, WRITE_STAGED, GATE_REQUESTED, GATE_DECIDED, LLM_CALL, USAGE, SCOPE_VIOLATION, AGENT_LOG;

    /** Who normally records this kind; callers that know better pass the source explicitly. */
    public RunStepSource defaultSource() {
        return this == TIMEOUT || this == COST_CAP_REACHED ? RunStepSource.PLATFORM : RunStepSource.AGENT;
    }
}
