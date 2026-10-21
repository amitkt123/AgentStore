package com.agentstore.platform.payments;

import java.math.BigDecimal;

/** {@code requestId} makes a retried request return the refund it already created. */
public record RefundRequest(String requestId, BigDecimal amount) {}
