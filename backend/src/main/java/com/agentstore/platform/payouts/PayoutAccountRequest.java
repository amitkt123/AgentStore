package com.agentstore.platform.payouts;

public record PayoutAccountRequest(PayoutAccountType type, String accountNumber, String ifsc, String vpa,
                                   String holderName) {}
