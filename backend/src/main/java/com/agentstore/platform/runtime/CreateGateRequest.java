package com.agentstore.platform.runtime;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record CreateGateRequest(@NotBlank @Size(max = 100) String action, @NotBlank @Size(max = 2000) String summary,
                                @NotNull Map<String, Object> payload, List<String> affectsResources,
                                @Positive @DecimalMax("9999999999") @Digits(integer = 10, fraction = 4) BigDecimal estimatedUnits) {
}
