package com.agentstore.platform.runtime;

import org.springframework.http.HttpStatus;

public class RuntimeApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public RuntimeApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
