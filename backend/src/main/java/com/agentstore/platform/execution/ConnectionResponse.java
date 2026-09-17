package com.agentstore.platform.execution;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ConnectionResponse(UUID id, UUID agentId, List<String> grantedScopes, BigDecimal spendCap,
                                  ConnectionStatus status) {
    static ConnectionResponse from(Connection c) {
        return new ConnectionResponse(c.getId(), c.getAgent().getId(), c.getGrantedScopes(), c.getSpendCap(), c.getStatus());
    }
}
