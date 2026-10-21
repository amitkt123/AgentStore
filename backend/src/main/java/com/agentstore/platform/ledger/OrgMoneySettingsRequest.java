package com.agentstore.platform.ledger;

import java.math.BigDecimal;

/** Null fields stay unchanged. */
public record OrgMoneySettingsRequest(BigDecimal feePercent, BillingMode billingMode, BigDecimal creditLimit) {
}
