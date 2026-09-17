package com.agentstore.platform.execution;

public interface SandboxProvider {
    SandboxHandle create(SandboxSpec spec);

    void kill(String sandboxId);
}
