package com.agentstore.platform.llm;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LlmCostTest {

    static LlmModel model(String in, String out) {
        LlmModel model = new LlmModel();
        model.setId("m");
        model.setInputPricePerMtok(new BigDecimal(in));
        model.setOutputPricePerMtok(new BigDecimal(out));
        model.setCurrency("USD");
        return model;
    }

    @Test
    void aPlatformCallCostsTokensTimesThePricePerMillion() {
        assertThat(LlmCost.of(LlmSource.PLATFORM, model("3.0000", "15.0000"), 100, 40)).isEqualByComparingTo("0.0009");
        assertThat(LlmCost.of(LlmSource.PLATFORM, model("3.0000", "15.0000"), 1_000_000, 1_000_000))
                .isEqualByComparingTo("18.0000");
    }

    @Test
    void theCostIsRoundedHalfUpToFourPlaces() {
        // 10 * 2.5 + 5 * 10 = 75 per million = 0.000075
        BigDecimal cost = LlmCost.of(LlmSource.PLATFORM, model("2.5000", "10.0000"), 10, 5);

        assertThat(cost).isEqualByComparingTo("0.0001");
        assertThat(cost.scale()).isEqualTo(4);
    }

    @Test
    void everyOtherSourceCostsNothing() {
        for (LlmSource source : new LlmSource[]{LlmSource.BYOK_PROXY, LlmSource.MOCK, LlmSource.REPLAY}) {
            assertThat(LlmCost.of(source, model("3.0000", "15.0000"), 5000, 5000)).isEqualByComparingTo("0");
        }
    }

    @Test
    void zeroPricesAndZeroTokensCostNothing() {
        assertThat(LlmCost.of(LlmSource.PLATFORM, model("0", "0"), 5000, 5000)).isEqualByComparingTo("0");
        assertThat(LlmCost.of(LlmSource.PLATFORM, model("3.0000", "15.0000"), 0, 0)).isEqualByComparingTo("0");
    }
}
