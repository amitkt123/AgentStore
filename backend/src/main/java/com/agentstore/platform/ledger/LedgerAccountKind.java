package com.agentstore.platform.ledger;

public enum LedgerAccountKind {
    BUYER_WALLET, BUYER_RECEIVABLE, DEVELOPER_PAYABLE,
    PLATFORM_REVENUE, PLATFORM_LLM_COST, PAYMENT_CLEARING, PLATFORM_BANK, RAZORPAYX_BALANCE,
    PAYOUT_CLEARING, TAX_TDS_PAYABLE, TAX_TCS_PAYABLE, GATEWAY_FEES, PAYOUT_FEES, EXTERNAL;

    /** True for accounts owned by the platform rather than a buyer or developer org. */
    public boolean isPlatform() {
        return !name().startsWith("BUYER_") && !name().startsWith("DEVELOPER_");
    }
}
