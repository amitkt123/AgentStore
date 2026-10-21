package com.agentstore.platform.payments;

import org.springframework.http.HttpStatus;

/** A payments failure with a stable code, rendered as {@code {code, message}} by {@link PaymentApiErrors}. */
public class PaymentApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public PaymentApiException(HttpStatus status, String code, String message) {
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
