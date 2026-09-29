package com.agentstore.platform.execution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One row of the buyer's run list. {@code held} is what the run still reserves on the wallet. */
public record RunListItem(UUID id, String displayId, String agent, String version, RunStatus status,
                          TerminalReason terminalReason, String reachedBlock, Instant startedAt, Instant completedAt,
                          BigDecimal charged, BigDecimal held, DecidedBy decidedBy) {

    public record DecidedBy(String name) {
    }
}
