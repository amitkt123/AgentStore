package com.agentstore.runner.backend;

import java.util.List;

/**
 * The only vendor seam in the runner. Implementations must pass {@code IsolationBackendConformanceTest}.
 * Vendor vocabulary (templates, envd, ...) stays inside implementations.
 */
public interface IsolationBackend {

    String name();

    /** Creates the sandbox and starts {@code launch.command()} through the run wrapper. Returns the sandbox id. */
    String create(SandboxLaunch launch);

    /** Never throws for "not found": that is {@link SandboxState#gone()}. Returns {@link SandboxState#unknown()} when the
     * backend cannot be asked. */
    SandboxState status(String sandboxId);

    /** At most {@code maxBytes} of combined stdout/stderr starting at byte {@code offset}. Empty chunk (same offset) when
     * nothing new or the sandbox is gone. */
    LogChunk readLogs(String sandboxId, long offset, int maxBytes);

    /** Idempotent: killing an unknown or already-gone sandbox is not an error. */
    void kill(String sandboxId);

    /** Live sandboxes carrying this runner's {@link Labels#RUNNER_ID} label. */
    List<OwnedSandbox> listOwned();
}
