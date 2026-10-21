package com.agentstore.platform.ledger;

import java.math.BigDecimal;
import java.util.UUID;

/** One line of a ledger transaction: positive raises the account's balance, negative lowers it. */
public record Posting(UUID accountId, BigDecimal amount) {
}
