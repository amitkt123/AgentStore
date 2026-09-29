package com.agentstore.platform.execution;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** {@code monthlyLimit} (null = none) and {@code approverUserIds} (null or empty = every approver) are optional. */
public record CreateConnectionRequest(@NotNull UUID agentId, @NotEmpty List<String> grantedScopes,
                                       @NotNull @Positive BigDecimal spendCap, @Positive BigDecimal monthlyLimit,
                                       List<UUID> approverUserIds) {

    public CreateConnectionRequest(UUID agentId, List<String> grantedScopes, BigDecimal spendCap) {
        this(agentId, grantedScopes, spendCap, null, null);
    }
}
