package com.agentstore.platform.runtime;

import java.math.BigDecimal;

public record UsageResponse(BigDecimal charged, BigDecimal totalCharged, BigDecimal costCapRemaining) {
}
