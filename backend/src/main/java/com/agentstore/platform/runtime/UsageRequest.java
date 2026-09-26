package com.agentstore.platform.runtime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record UsageRequest(@NotNull @Positive BigDecimal quantity, @NotBlank @Size(max = 100) String unit,
                           @Size(max = 200) String outcomeRef) {
}
