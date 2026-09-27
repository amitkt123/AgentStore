package com.agentstore.platform.sandbox;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** A developer-sandbox failure with a stable code; {@code extra} fields are added to the response body. */
public class SandboxApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> extra;

    public SandboxApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public SandboxApiException(HttpStatus status, String code, String message, Map<String, Object> extra) {
        super(message);
        this.status = status;
        this.code = code;
        this.extra = extra;
    }

    public static SandboxApiException quota(String limit, Object used, Object max) {
        return new SandboxApiException(HttpStatus.TOO_MANY_REQUESTS, "SANDBOX_QUOTA_EXCEEDED",
                "Sandbox limit reached: " + limit, Map.of("limit", limit, "used", used, "max", max));
    }

    public static SandboxApiException notFound(String what) {
        return new SandboxApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
    }

    public static SandboxApiException invalid(String message) {
        return new SandboxApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> extra() {
        return extra;
    }
}
