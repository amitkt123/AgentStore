package com.agentstore.runner.backend.e2b;

/** Never carries the request (it holds run secrets). */
public class ShimException extends RuntimeException {

    private final String code;

    public ShimException(String message) {
        this(message, (String) null);
    }

    public ShimException(String message, String code) {
        super(message);
        this.code = code;
    }

    public ShimException(String message, Throwable cause) {
        super(message, cause);
        this.code = null;
    }

    /** The shim's {@code error.code}, or null when the failure happened outside the shim's own error handling. */
    public String code() {
        return code;
    }
}
