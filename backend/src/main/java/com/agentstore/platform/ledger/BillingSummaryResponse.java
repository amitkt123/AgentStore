package com.agentstore.platform.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A buyer org's run charges for one UTC month ({@code month} is YYYY-MM), grouped by agent. */
public record BillingSummaryResponse(String month, BigDecimal total, List<AgentLines> agents) {

    public record AgentLines(UUID agentId, String agent, int runs, BigDecimal total, List<Line> lines) {
    }

    /** One run: what it was charged, in the manifest's unit, and why it ended and who approved it if that matters. */
    public record Line(String runDisplayId, BigDecimal units, String unitDisplay, BigDecimal amount,
                       String terminalReason, UUID decidedBy, Instant at) {
    }
}
