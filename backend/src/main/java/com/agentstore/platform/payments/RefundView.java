package com.agentstore.platform.payments;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RefundView(UUID id, BigDecimal amount, String status, List<PartView> parts, Instant createdAt) {

    public record PartView(UUID id, UUID paymentOrderId, PaymentMethod method, BigDecimal amount, String status,
                           String failureReason) {}
}
