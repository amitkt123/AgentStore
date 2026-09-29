package com.agentstore.platform.execution;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ConnectionSummaryResponse(UUID id, UUID agentId, String agentName, List<String> grantedScopes,
                                         BigDecimal spendCap, ConnectionStatus status, BigDecimal monthlyLimit,
                                         List<UUID> approverUserIds, BigDecimal monthToDate) {
    static ConnectionSummaryResponse from(Connection c, BigDecimal monthToDate) {
        return new ConnectionSummaryResponse(c.getId(), c.getAgent().getId(), c.getAgent().getName(),
                c.getGrantedScopes(), c.getSpendCap(), c.getStatus(), c.getMonthlyLimit(),
                c.getApproverUserIds().stream().sorted().toList(), monthToDate);
    }
}
