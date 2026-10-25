package com.agentstore.platform.payouts;

import java.math.BigDecimal;

public record PayoutsSummaryResponse(PayoutAccountView account, BigDecimal available, BigDecimal minimum,
                                     BigDecimal dailyLimit, BigDecimal withdrawnToday, boolean canWithdraw,
                                     String blockedReason, int validationAttemptsLeft, String provider,
                                     String verifiedName) {}
