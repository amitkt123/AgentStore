package com.agentstore.platform.ledger;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties("agentstore.money")
public record MoneyProperties(
        BigDecimal platformFeePercent,
        BillingMode billingMode,
        BigDecimal postpaidDefaultCreditLimit,
        Tax tds,
        Tax gstTcs) {

    /** {@code rateNoPan} is only used by TDS. */
    public record Tax(boolean enabled, BigDecimal rate, BigDecimal rateNoPan) {}
}
