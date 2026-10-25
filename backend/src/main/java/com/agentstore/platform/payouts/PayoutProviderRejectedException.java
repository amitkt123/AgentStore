package com.agentstore.platform.payouts;

/** The provider definitively rejected the request (a 4xx-style answer); retrying will not help. */
public class PayoutProviderRejectedException extends RuntimeException {
    public PayoutProviderRejectedException(String message) {
        super(message);
    }

    public PayoutProviderRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
