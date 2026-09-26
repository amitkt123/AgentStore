package com.agentstore.platform.runtime;

import org.springframework.http.HttpStatus;

import java.util.List;

public class RuntimeApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<?> errors;

    public RuntimeApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    /** {@code errors} carries structured detail (for example row errors) into the problem body. */
    public RuntimeApiException(HttpStatus status, String code, String message, List<?> errors) {
        super(message);
        this.status = status;
        this.code = code;
        this.errors = errors;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<?> errors() {
        return errors;
    }
}
