package com.agentstore.platform.ledger;

import java.math.BigDecimal;

public record PlatformAccountResponse(LedgerAccountKind kind, BigDecimal balance) {
}
