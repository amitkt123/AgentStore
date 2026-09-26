package com.agentstore.platform.build;

/** The runner could not be asked. Callers leave the build as it is and try again on the next tick. */
public class BuildRunnerUnavailableException extends RuntimeException {
    public BuildRunnerUnavailableException(String message) {
        super(message);
    }
}
