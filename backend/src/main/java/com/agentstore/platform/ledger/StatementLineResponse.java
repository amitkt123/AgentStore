package com.agentstore.platform.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record StatementLineResponse(UUID runId, String agentName, BigDecimal amount, LedgerEntryType type,
                                     Instant createdAt) {
}
