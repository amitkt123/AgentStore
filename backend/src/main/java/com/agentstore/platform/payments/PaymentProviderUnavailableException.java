package com.agentstore.platform.payments;

/** The provider could not be reached or answered 5xx or timed out; the outcome is unknown and may be retried. */
public class PaymentProviderUnavailableException extends RuntimeException {
    public PaymentProviderUnavailableException(String message) {
        super(message);
    }

    public PaymentProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
