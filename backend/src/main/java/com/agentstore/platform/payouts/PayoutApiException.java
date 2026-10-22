package com.agentstore.platform.payouts;

import org.springframework.http.HttpStatus;

/** A payouts failure with a stable code, rendered as {@code {code, message}} by {@link PayoutApiErrors}. */
public class PayoutApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public PayoutApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public PayoutApiException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
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
