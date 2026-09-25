package com.agentstore.runner.backend.e2b;

/** Never carries the request (it holds run secrets). */
public class ShimException extends RuntimeException {
    public ShimException(String message) { super(message); }
    public ShimException(String message, Throwable cause) { super(message, cause); }
}
