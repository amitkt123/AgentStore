package com.agentstore.runner.backend;

import java.util.List;

/**
 * The only vendor seam in the runner. Implementations must pass {@code IsolationBackendConformanceTest}.
 * Vendor vocabulary stays inside implementations.
 */
public interface IsolationBackend {

    String name();

    /** Creates the sandbox and starts {@code launch.command()} through the run wrapper. Returns the sandbox id. */
    String create(SandboxLaunch launch);

    /**
     * Builds a runnable image from {@code build.appDir()} following the shared recipe (spec §4 step 4): the runtime's
     * base image, the app at /workspace/app, dependency install, dependency audit, entrypoint smoke test. Returns an
     * imageRef that {@link #create} accepts. Throws {@link BuildFailedException} (INSTALL_FAILED, DEP_VULN,
     * SMOKE_FAILED, TIMEOUT or BUILD_FAILED).
     */
    String buildImage(ImageBuild build, BuildLog log);

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
