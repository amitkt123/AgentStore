package com.agentstore.platform.data;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** A buyer-API failure with a stable code; {@code extra} fields are added to the response body. */
public class DataApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> extra;

    public DataApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public DataApiException(HttpStatus status, String code, String message, Map<String, Object> extra) {
        super(message);
        this.status = status;
        this.code = code;
        this.extra = extra;
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
