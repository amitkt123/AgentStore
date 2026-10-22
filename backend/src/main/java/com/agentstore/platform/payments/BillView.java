package com.agentstore.platform.payments;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BillView(UUID id, String period, BigDecimal amount, String status, Instant dueAt, Instant paidAt) {

    public static BillView of(BuyerBill b) {
        return new BillView(b.getId(), b.getPeriod().trim(), b.getAmount(), b.getStatus().name(), b.getDueAt(),
                b.getPaidAt());
    }
}
