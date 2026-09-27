package com.agentstore.platform.llm;

import java.time.Duration;

/**
 * One failed provider attempt. Deliberately carries no cause and no response body: bodies can echo the prompt or the
 * key, so only the kind, the HTTP status and a {@code Retry-After} hint survive.
 */
public class ProviderCallException extends Exception {

    public enum Kind { RATE_LIMITED, SERVER_ERROR, CLIENT_ERROR, TIMEOUT, IO, BAD_RESPONSE }

    private final Kind kind;
    private final int status;
    private final Duration retryAfter;

    public ProviderCallException(Kind kind, int status, Duration retryAfter) {
        super("LLM provider call failed: " + kind + (status > 0 ? " (HTTP " + status + ")" : ""));
        this.kind = kind;
        this.status = status;
        this.retryAfter = retryAfter;
    }

    public Kind kind() {
        return kind;
    }

    public int status() {
        return status;
    }

    /** The provider's {@code Retry-After} in seconds, when it sent one. */
    public Duration retryAfter() {
        return retryAfter;
    }

    /** 429, 5xx, timeouts and I/O errors are retried once. */
    public boolean retryable() {
        return kind == Kind.RATE_LIMITED || kind == Kind.SERVER_ERROR || kind == Kind.TIMEOUT || kind == Kind.IO;
    }

    /** Only the retryable kinds count towards the circuit breaker; other 4xx and odd replies do not. */
    public boolean countsAsBreakerFailure() {
        return retryable();
    }
}
