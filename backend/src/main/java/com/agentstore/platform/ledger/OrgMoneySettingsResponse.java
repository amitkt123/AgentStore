package com.agentstore.platform.ledger;

import com.agentstore.platform.identity.OrgType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** An org's own money settings (null = not overridden) next to the values in effect. */
public record OrgMoneySettingsResponse(UUID orgId, String name, OrgType type, BigDecimal feePercent,
                                       BigDecimal effectiveFeePercent, BillingMode billingMode,
                                       BillingMode effectiveBillingMode, BigDecimal creditLimit,
                                       BigDecimal walletBalance, BigDecimal payableBalance,
                                       Instant moneyFrozenAt, String moneyFrozenReason) {
}
