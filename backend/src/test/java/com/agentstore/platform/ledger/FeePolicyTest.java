package com.agentstore.platform.ledger;

import com.agentstore.platform.catalog.Agent;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FeePolicyTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    private static FeePolicy policy(boolean tdsOn, boolean tcsOn) {
        return new FeePolicy(new MoneyProperties(d("20"), BillingMode.PREPAID, d("50000.00"),
                new MoneyProperties.Tax(tdsOn, d("0.001"), d("0.05")),
                new MoneyProperties.Tax(tcsOn, d("0.01"), null)));
    }

    @Test
    void splitsHundredAtTwentyPercent() {
        FeePolicy.Split s = policy(false, false).split(d("100.00"), d("20"), true);
        assertThat(s.developer()).isEqualByComparingTo("80.00");
        assertThat(s.platformFee()).isEqualByComparingTo("20.00");
        assertThat(s.tds()).isEqualByComparingTo("0.00");
        assertThat(s.gstTcs()).isEqualByComparingTo("0.00");
    }

    @Test
    void oddAmountRemainderGoesToDeveloper() {
        FeePolicy p = policy(false, false);
        FeePolicy.Split a = p.split(d("33.33"), d("20"), true);
        assertThat(a.platformFee()).isEqualByComparingTo("6.67");
        assertThat(a.developer()).isEqualByComparingTo("26.66");
        FeePolicy.Split b = p.split(d("0.01"), d("20"), true);
        assertThat(b.platformFee()).isEqualByComparingTo("0.00");
        assertThat(b.developer()).isEqualByComparingTo("0.01");
    }

    @Test
    void taxesWhenEnabled() {
        FeePolicy p = policy(true, true);
        FeePolicy.Split s = p.split(d("1000.00"), d("20"), true);
        assertThat(s.developer()).isEqualByComparingTo("789.00");
        assertThat(s.platformFee()).isEqualByComparingTo("200.00");
        assertThat(s.tds()).isEqualByComparingTo("1.00");
        assertThat(s.gstTcs()).isEqualByComparingTo("10.00");
        FeePolicy.Split n = p.split(d("1000.00"), d("20"), false);
        assertThat(n.tds()).isEqualByComparingTo("50.00");
        assertThat(n.developer()).isEqualByComparingTo("740.00");
        assertThat(n.developer().add(n.platformFee()).add(n.tds()).add(n.gstTcs())).isEqualByComparingTo("1000.00");
    }

    @Test
    void feePercentPrefersAgentThenOrgThenConfig() {
        FeePolicy p = policy(false, false);
        Org org = new Org(UUID.randomUUID(), "dev", OrgType.DEVELOPER);
        Agent agent = new Agent(UUID.randomUUID(), org, "a", "c", "d");
        assertThat(p.feePercent(agent)).isEqualByComparingTo("20");
        org.setPlatformFeePercent(d("15"));
        assertThat(p.feePercent(agent)).isEqualByComparingTo("15");
        agent.setPlatformFeePercent(d("10"));
        assertThat(p.feePercent(agent)).isEqualByComparingTo("10");
    }
}
