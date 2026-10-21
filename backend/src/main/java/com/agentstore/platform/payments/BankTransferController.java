package com.agentstore.platform.payments;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/wallet")
public class BankTransferController {

    private final BankTransferService bankTransfers;
    private final PaymentAccess access;

    public BankTransferController(BankTransferService bankTransfers, PaymentAccess access) {
        this.bankTransfers = bankTransfers;
        this.access = access;
    }

    @GetMapping("/bank-transfer-details")
    @PreAuthorize("hasRole('BUYER_ADMIN')")
    public BankTransferDetailsResponse details(@PathVariable UUID orgId) {
        access.requireOwnOrgAdmin(orgId);
        return bankTransfers.details(orgId);
    }
}
