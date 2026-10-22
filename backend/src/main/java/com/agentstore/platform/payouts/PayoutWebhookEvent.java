package com.agentstore.platform.payouts;

import java.math.BigDecimal;

/** A provider-neutral payout webhook event; unused fields are null. */
public record PayoutWebhookEvent(
        String eventId,
        Kind kind,
        String payoutId,
        String validationId,
        BigDecimal amount,
        BigDecimal fees,
        String utr,
        String registeredName,
        String failureReason) {

    public enum Kind {
        PAYOUT_PROCESSED, PAYOUT_REVERSED, PAYOUT_FAILED, VALIDATION_COMPLETED, VALIDATION_FAILED, OTHER
    }
}
