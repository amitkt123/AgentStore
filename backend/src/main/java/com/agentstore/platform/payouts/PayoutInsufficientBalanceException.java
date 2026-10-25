package com.agentstore.platform.payouts;

/** The platform's own provider (RazorpayX) balance is too low to fund the payout; nothing was sent. */
public class PayoutInsufficientBalanceException extends RuntimeException {
    public PayoutInsufficientBalanceException(String message) {
        super(message);
    }

    public PayoutInsufficientBalanceException(String message, Throwable cause) {
        super(message, cause);
    }
}
