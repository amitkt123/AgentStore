package com.agentstore.platform.manifest;

import org.springframework.http.HttpStatus;

public class ManifestApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ManifestApiException(HttpStatus status, String code, String message) {
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
