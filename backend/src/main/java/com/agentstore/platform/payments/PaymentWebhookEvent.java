package com.agentstore.platform.payments;

import java.math.BigDecimal;

/** A provider-neutral webhook event; unused fields are null. */
public record PaymentWebhookEvent(
        String eventId,
        Kind kind,
        String orderId,
        String paymentId,
        String refundId,
        String virtualAccountId,
        BigDecimal amount,
        PaymentMethod method,
        String failureReason) {

    public enum Kind {
        PAYMENT_CAPTURED, PAYMENT_FAILED, ORDER_PAID, VIRTUAL_ACCOUNT_CREDITED, REFUND_PROCESSED, REFUND_FAILED, OTHER
    }
}
