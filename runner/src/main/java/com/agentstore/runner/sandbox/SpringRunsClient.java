package com.agentstore.runner.sandbox;

import java.util.Optional;
import java.util.Set;

/** Asks Spring which runs are still active. Empty when Spring cannot be reached (callers must then do nothing). */
@FunctionalInterface
public interface SpringRunsClient {
    Optional<Set<String>> activeRunIds();
}
