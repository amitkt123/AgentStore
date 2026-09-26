package com.agentstore.runner.backend;

/** Where a build writes its progress. Implementations redact known secret formats. */
@FunctionalInterface
public interface BuildLog {
    void append(String line);
}
