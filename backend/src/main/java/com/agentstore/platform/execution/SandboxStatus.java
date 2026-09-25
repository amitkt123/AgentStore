package com.agentstore.platform.execution;

/** UNKNOWN means the provider could not be asked; callers must not treat it as stopped. */
public record SandboxStatus(State state, Integer exitCode) {

    public enum State { RUNNING, EXITED, GONE, UNKNOWN }

    public static final SandboxStatus RUNNING = new SandboxStatus(State.RUNNING, null);
    public static final SandboxStatus GONE = new SandboxStatus(State.GONE, null);
    public static final SandboxStatus UNKNOWN = new SandboxStatus(State.UNKNOWN, null);

    public static SandboxStatus exited(int exitCode) {
        return new SandboxStatus(State.EXITED, exitCode);
    }

    public boolean stopped() {
        return state == State.EXITED || state == State.GONE;
    }
}
