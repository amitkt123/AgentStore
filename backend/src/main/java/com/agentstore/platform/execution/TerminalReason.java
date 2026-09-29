package com.agentstore.platform.execution;

/** Why a run ended. RunStatus says where it ended; this says why (spec WS1b section 2). */
public enum TerminalReason {
    FINISHED, GATE_REJECTED, GATE_EXPIRED, COST_CAP_REACHED, TIMED_OUT, SCOPE_VIOLATIONS, AGENT_FAILED, CANCELLED,
    SANDBOX_LOST
}
