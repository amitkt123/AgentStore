package com.agentstore.platform.payments;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentOrderView(UUID id, String purpose, PaymentMethod method, BigDecimal amount, String status,
                               BigDecimal refundedAmount, Instant createdAt, Instant paidAt) {

    public static PaymentOrderView of(PaymentOrder o) {
        return new PaymentOrderView(o.getId(), o.getPurpose().name(), o.getMethod(), o.getAmount(),
                o.getStatus().name(), o.getRefundedAmount(), o.getCreatedAt(), o.getPaidAt());
    }
}
