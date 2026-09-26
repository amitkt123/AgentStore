package com.agentstore.platform.runtime;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * {@code quantity} is bounded to what {@code usage_event.quantity} (NUMERIC(14,4)) can store, so an absurd exponent is
 * a 400 up front instead of a database error or minutes of BigDecimal arithmetic.
 */
public record UsageRequest(@NotNull @Positive @DecimalMax("9999999999") @Digits(integer = 10, fraction = 4) BigDecimal quantity,
                           @NotBlank @Size(max = 100) String unit, @Size(max = 200) String outcomeRef) {
}
