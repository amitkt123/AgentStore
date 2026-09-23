package com.agentstore.platform.execution;

/** READ..FAILED are reported by the agent; TIMEOUT and COST_CAP_REACHED are recorded by the platform only. */
public enum RunStepKind { READ, WRITE, GATE, COST, COMPLETE, FAILED, TIMEOUT, COST_CAP_REACHED }
