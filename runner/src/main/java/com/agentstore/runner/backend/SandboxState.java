package com.agentstore.runner.backend;

public record SandboxState(Phase phase, Integer exitCode) {

    public enum Phase { RUNNING, EXITED, GONE, UNKNOWN }

    public static SandboxState running() { return new SandboxState(Phase.RUNNING, null); }
    public static SandboxState exited(int exitCode) { return new SandboxState(Phase.EXITED, exitCode); }
    public static SandboxState gone() { return new SandboxState(Phase.GONE, null); }
    public static SandboxState unknown() { return new SandboxState(Phase.UNKNOWN, null); }

    public boolean isTerminal() { return phase == Phase.EXITED || phase == Phase.GONE; }
}
