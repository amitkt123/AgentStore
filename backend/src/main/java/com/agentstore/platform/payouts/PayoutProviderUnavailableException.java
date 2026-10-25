package com.agentstore.platform.payouts;

/** The provider could not be reached, answered 5xx, timed out or refused our credentials (401/403); the outcome is unknown and may be retried. */
public class PayoutProviderUnavailableException extends RuntimeException {
    public PayoutProviderUnavailableException(String message) {
        super(message);
    }

    public PayoutProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
