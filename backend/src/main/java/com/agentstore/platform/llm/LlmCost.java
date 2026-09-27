package com.agentstore.platform.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** {@code in/1e6 × input price + out/1e6 × output price}, scale 4. Only PLATFORM calls cost us anything. */
final class LlmCost {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private LlmCost() {
    }

    static BigDecimal of(LlmSource source, LlmModel model, int inputTokens, int outputTokens) {
        if (source != LlmSource.PLATFORM) {
            return BigDecimal.ZERO.setScale(4);
        }
        return BigDecimal.valueOf(inputTokens).multiply(model.getInputPricePerMtok())
                .add(BigDecimal.valueOf(outputTokens).multiply(model.getOutputPricePerMtok()))
                .divide(MILLION, 4, RoundingMode.HALF_UP);
    }
}
