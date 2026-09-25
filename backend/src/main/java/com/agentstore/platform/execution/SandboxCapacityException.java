package com.agentstore.platform.execution;

/** The runner refused to start a sandbox right now (CAPACITY_EXHAUSTED) or this month (BUDGET_EXHAUSTED). */
public class SandboxCapacityException extends RuntimeException {
    private final String code;

    public SandboxCapacityException(String code) {
        super("Sandbox runner refused: " + code);
        this.code = code;
    }

    public String code() { return code; }
}
