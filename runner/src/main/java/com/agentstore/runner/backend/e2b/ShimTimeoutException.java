package com.agentstore.runner.backend.e2b;

/** The shim did not finish within its call timeout and was killed. */
public class ShimTimeoutException extends ShimException {
    public ShimTimeoutException(String message) {
        super(message);
    }
}
