package com.agentstore.platform.execution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record FlowView(List<FlowBlock> blocks, String reachedBlock, Spend spend, Instant deadlineAt,
                       long blockedAttempts) {

    public record Spend(BigDecimal charged, BigDecimal cap) {
    }
}
