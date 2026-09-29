package com.agentstore.platform.execution;

import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A null field is left as it is. {@code clearMonthlyLimit} removes the limit, and an empty {@code approverUserIds}
 * removes the list (every approver may decide again).
 */
public record UpdateConnectionRequest(@Positive BigDecimal monthlyLimit, Boolean clearMonthlyLimit,
                                       List<UUID> approverUserIds) {
}
