package com.agentstore.platform.execution;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ConnectionSummaryResponse(UUID id, UUID agentId, String agentName, List<String> grantedScopes,
                                         BigDecimal spendCap, ConnectionStatus status) {
    static ConnectionSummaryResponse from(Connection c) {
        return new ConnectionSummaryResponse(c.getId(), c.getAgent().getId(), c.getAgent().getName(),
                c.getGrantedScopes(), c.getSpendCap(), c.getStatus());
    }
}
