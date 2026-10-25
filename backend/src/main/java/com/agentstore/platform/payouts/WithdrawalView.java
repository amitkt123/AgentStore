package com.agentstore.platform.payouts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A withdrawal as the developer sees it. {@code accountLabel} is "Bank ••1234" or the VPA, never a full number. */
public record WithdrawalView(UUID id, BigDecimal amount, String mode, String status, String utr, String accountLabel,
                             String failureReason, Instant createdAt, Instant processedAt) {

    static WithdrawalView of(Withdrawal w, PayoutAccount account) {
        return new WithdrawalView(w.getId(), w.getAmount(), w.getMode().name(), w.getStatus().name(), w.getUtr(),
                label(account), w.getFailureReason(), w.getCreatedAt(), w.getProcessedAt());
    }

    static String label(PayoutAccount a) {
        if (a == null) return null;
        return a.getType() == PayoutAccountType.BANK ? "Bank ••" + a.getAccountLast4() : a.getVpa();
    }
}
