package com.agentstore.platform.execution;

import java.math.BigDecimal;

/** What a usage report cost. {@code capReached} means the request would have exceeded the cap: the remainder was charged and the run stopped. */
public record UsageOutcome(BigDecimal charged, BigDecimal totalCharged, BigDecimal costCapRemaining, boolean capReached) {
}
