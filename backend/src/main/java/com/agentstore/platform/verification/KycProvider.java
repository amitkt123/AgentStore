package com.agentstore.platform.verification;

import java.util.UUID;

/** The seam between an identity check's verdict and how it was reached. {@link ManualKycProvider} is the only
 * implementation today — a human enters PASS/FAIL directly; a future automated vendor integration for PAN/GSTIN
 * or phone would implement this same contract without any change to the caller. */
public interface KycProvider {

    VerificationCheck check(UUID verificationId, CheckType type, boolean passed, String note, UUID adminUserId);
}
