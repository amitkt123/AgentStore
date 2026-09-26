package com.agentstore.runner.backend;

import java.nio.file.Path;

/** Input to {@link IsolationBackend#buildImage}: an unpacked, scanned app directory and how to run it. */
public record ImageBuild(String buildId, Path appDir, AgentRuntime runtime, String entrypoint) {}
