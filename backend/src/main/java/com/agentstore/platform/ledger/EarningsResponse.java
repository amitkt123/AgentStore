package com.agentstore.platform.ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A developer org's earnings for one UTC month ({@code month} is YYYY-MM); {@code available} is the all-time payable balance. */
public record EarningsResponse(String month, BigDecimal buyersPaid, BigDecimal platformFee, BigDecimal feePercent,
                               BigDecimal aiUsage, BigDecimal tax, BigDecimal earned, BigDecimal available,
                               List<ByAgent> byAgent) {

    public record ByAgent(UUID agentId, String agent, int runs, BigDecimal buyersPaid, BigDecimal fee,
                          BigDecimal aiUsage, BigDecimal earned, BigDecimal perRun) {
    }
}
