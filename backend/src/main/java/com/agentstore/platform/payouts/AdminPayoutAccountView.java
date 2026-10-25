package com.agentstore.platform.payouts;

import java.time.Instant;
import java.util.UUID;

/** A payout account as an admin sees it: the owner's view plus the org and its verified legal name. */
public record AdminPayoutAccountView(UUID id, String type, String accountLast4, String ifsc, String vpa,
                                     String holderName, String validatedName, Boolean nameMatch, String status,
                                     String failureReason, Instant createdAt, Instant verifiedAt,
                                     UUID orgId, String orgName, String legalName) {

    static AdminPayoutAccountView of(PayoutAccountView v, UUID orgId, String orgName, String legalName) {
        return new AdminPayoutAccountView(v.id(), v.type(), v.accountLast4(), v.ifsc(), v.vpa(), v.holderName(),
                v.validatedName(), v.nameMatch(), v.status(), v.failureReason(), v.createdAt(), v.verifiedAt(),
                orgId, orgName, legalName);
    }
}
