package com.agentstore.platform.payments;

public record BankTransferDetailsResponse(String accountNumber, String ifsc, String beneficiaryName) {}
