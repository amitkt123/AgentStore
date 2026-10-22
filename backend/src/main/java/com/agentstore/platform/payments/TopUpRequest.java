package com.agentstore.platform.payments;

import java.math.BigDecimal;

public record TopUpRequest(BigDecimal amount, PaymentMethod method) {}
