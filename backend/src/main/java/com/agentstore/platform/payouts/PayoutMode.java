package com.agentstore.platform.payouts;

/** How a payout travels: a BANK account is paid by IMPS, a VPA by UPI. */
public enum PayoutMode {
    IMPS, UPI;

    public static PayoutMode forType(PayoutAccountType type) {
        return type == PayoutAccountType.BANK ? IMPS : UPI;
    }
}
