package com.agentstore.platform.verification;

import java.time.Instant;

record VerificationCheckView(CheckType type, boolean passed, String note, Instant checkedAt) {

    static VerificationCheckView from(VerificationCheck c) {
        return new VerificationCheckView(c.getCheckType(), c.isPassed(), c.getNote(), c.getCheckedAt());
    }
}
