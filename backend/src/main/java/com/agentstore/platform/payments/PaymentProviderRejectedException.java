package com.agentstore.platform.payments;

/** The provider definitively rejected the request (a 4xx-style answer); retrying will not help. */
public class PaymentProviderRejectedException extends RuntimeException {
    public PaymentProviderRejectedException(String message) {
        super(message);
    }

    public PaymentProviderRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
