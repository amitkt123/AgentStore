package com.agentstore.platform.ledger;

import java.math.BigDecimal;

/** {@code requestId} is the client's idempotency key; {@code amount} may be negative (a debit). */
public record AdjustmentRequest(String requestId, BigDecimal amount, String note) {
}
