package com.agentstore.platform.ledger;

import com.agentstore.platform.catalog.Agent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class FeePolicy {

    public record Split(BigDecimal developer, BigDecimal platformFee, BigDecimal tds, BigDecimal gstTcs) {}

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final MoneyProperties props;

    public FeePolicy(MoneyProperties props) {
        this.props = props;
    }

    /** Agent override, else the developer org's override, else the configured default. */
    public BigDecimal feePercent(Agent agent) {
        if (agent.getPlatformFeePercent() != null) return agent.getPlatformFeePercent();
        if (agent.getDeveloperOrg() != null && agent.getDeveloperOrg().getPlatformFeePercent() != null) {
            return agent.getDeveloperOrg().getPlatformFeePercent();
        }
        return props.platformFeePercent();
    }

    public Split split(BigDecimal charge, BigDecimal feePercent, boolean developerHasPan) {
        BigDecimal fee = charge.multiply(feePercent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal tds = ZERO;
        MoneyProperties.Tax tdsCfg = props.tds();
        if (tdsCfg != null && tdsCfg.enabled()) {
            BigDecimal rate = developerHasPan ? tdsCfg.rate() : tdsCfg.rateNoPan();
            tds = charge.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal tcs = ZERO;
        MoneyProperties.Tax tcsCfg = props.gstTcs();
        if (tcsCfg != null && tcsCfg.enabled()) {
            tcs = charge.multiply(tcsCfg.rate()).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal developer = charge.subtract(fee).subtract(tds).subtract(tcs);
        return new Split(developer, fee, tds, tcs);
    }
}
