package com.agentstore.platform.manifest;

import java.math.BigDecimal;

public record CostEnvelope(String currency, BigDecimal maxPerRun, PricingUnit pricingUnit,
                            BigDecimal unitPrice, String unitLabel) {
}
