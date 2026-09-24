package com.agentstore.platform.execution;

public interface SandboxProvider {
    SandboxHandle create(SandboxSpec spec);

    void kill(String sandboxId);

    /** UNKNOWN means the provider could not be asked; callers must not treat it as exited. */
    SandboxStatus status(String sandboxId);
}
