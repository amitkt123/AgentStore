package com.agentstore.platform.payouts;

import java.time.Instant;
import java.util.UUID;

/** A payout account as shown to its owner. The full account number is never included. */
public record PayoutAccountView(UUID id, String type, String accountLast4, String ifsc, String vpa,
                                String holderName, String validatedName, Boolean nameMatch, String status,
                                String failureReason, Instant createdAt, Instant verifiedAt) {

    static PayoutAccountView of(PayoutAccount a) {
        return new PayoutAccountView(a.getId(), a.getType().name(), a.getAccountLast4(), a.getIfsc(), a.getVpa(),
                a.getHolderName(), a.getValidatedName(), a.getNameMatch(), a.getStatus().name(),
                a.getFailureReason(), a.getCreatedAt(), a.getVerifiedAt());
    }
}
