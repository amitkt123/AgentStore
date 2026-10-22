package com.agentstore.platform.payments;

import java.math.BigDecimal;
import java.util.List;

public record PaymentOptionsResponse(String provider, List<PaymentMethod> methods, BigDecimal topUpMin,
                                     BigDecimal topUpDailyLimit, BigDecimal topUpRemainingToday) {}
