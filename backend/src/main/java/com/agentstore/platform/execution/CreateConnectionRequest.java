package com.agentstore.platform.execution;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CreateConnectionRequest(@NotNull UUID agentId, @NotEmpty List<String> grantedScopes,
                                       @NotNull @Positive BigDecimal spendCap) {
}
