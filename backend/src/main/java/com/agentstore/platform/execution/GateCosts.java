package com.agentstore.platform.execution;

import com.agentstore.platform.manifest.ManifestParser;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** The "Cost if approved" figure. Computed by the platform from the agent's declared price; never supplied by the agent. */
public final class GateCosts {

    private GateCosts() {
    }

    public static BigDecimal costIfApproved(Run run, GateRequest gate, ManifestParser manifestParser) {
        if (gate.getEstimatedUnits() == null) {
            return null;
        }
        BigDecimal unitPrice = manifestParser.parse(run.getAgentVersion().getManifestJson()).costEnvelope().unitPrice();
        BigDecimal requested = gate.getEstimatedUnits().multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
        BigDecimal spent = run.getConnection() == null ? run.getSimulatedCharged() : run.getTotalCharged();
        BigDecimal remaining = run.getCostCap().subtract(spent).max(BigDecimal.ZERO);
        return requested.min(remaining);
    }
}
