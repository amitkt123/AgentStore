package com.agentstore.platform.payouts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A withdrawal as an admin sees it: the developer's view plus the org. */
public record AdminWithdrawalView(UUID id, BigDecimal amount, String mode, String status, String utr,
                                  String accountLabel, String failureReason, Instant createdAt, Instant processedAt,
                                  UUID orgId, String orgName) {

    static AdminWithdrawalView of(WithdrawalView v, UUID orgId, String orgName) {
        return new AdminWithdrawalView(v.id(), v.amount(), v.mode(), v.status(), v.utr(), v.accountLabel(),
                v.failureReason(), v.createdAt(), v.processedAt(), orgId, orgName);
    }
}
