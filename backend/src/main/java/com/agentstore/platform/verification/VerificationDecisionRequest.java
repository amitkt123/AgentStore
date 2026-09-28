package com.agentstore.platform.verification;

import java.util.List;

public record VerificationDecisionRequest(VerificationStatus outcome, String notes, List<CheckInput> checks) {

    public record CheckInput(CheckType type, boolean passed, String note) {
    }
}
