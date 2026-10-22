package com.agentstore.platform.payments;

import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.ledger.BillingMode;
import com.agentstore.platform.ledger.BillingModeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** The org's own virtual bank account, created once at the provider and stored under a single row per org. */
@Service
public class BankTransferService {

    private static final Logger log = LoggerFactory.getLogger(BankTransferService.class);

    private final PaymentProvider provider;
    private final TopUpService topUps;
    private final BillingModeService billingModes;
    private final VirtualAccountRepository accounts;
    private final OrgRepository orgs;
    private final TransactionTemplate tx;

    public BankTransferService(PaymentProvider provider, TopUpService topUps, BillingModeService billingModes,
                               VirtualAccountRepository accounts, OrgRepository orgs, TransactionTemplate tx) {
        this.provider = provider;
        this.topUps = topUps;
        this.billingModes = billingModes;
        this.accounts = accounts;
        this.orgs = orgs;
        this.tx = tx;
    }

    public BankTransferDetailsResponse details(UUID orgId) {
        if (!topUps.bankTransferEnabled()) {
            throw new PaymentApiException(HttpStatus.NOT_FOUND, "BANK_TRANSFER_DISABLED",
                    "Bank transfer is not available");
        }
        if (billingModes.modeFor(orgId) == BillingMode.POSTPAID) {
            throw new PaymentApiException(HttpStatus.CONFLICT, "POSTPAID_NO_WALLET",
                    "Postpaid orgs have no wallet to top up; pay the bill instead");
        }
        VirtualAccount existing = accounts.findById(orgId).orElse(null);
        if (existing != null) return view(existing);

        String orgName = orgs.findById(orgId).map(Org::getName)
                .orElseThrow(() -> new PaymentApiException(HttpStatus.NOT_FOUND, "ORG_NOT_FOUND", "Unknown org"));
        // The provider call stays outside any transaction.
        PaymentProvider.ProviderVirtualAccount va;
        try {
            va = provider.createVirtualAccount(orgId, orgName);
        } catch (PaymentProviderRejectedException | PaymentProviderUnavailableException e) {
            log.warn("Provider createVirtualAccount failed for org {}: {}", orgId, e.getMessage());
            throw new PaymentApiException(HttpStatus.BAD_GATEWAY, "PROVIDER_UNAVAILABLE",
                    "The payment provider is unavailable; try again shortly", e);
        }
        // A concurrent creator may win the insert; read back whichever row is stored.
        VirtualAccount stored = tx.execute(s -> {
            accounts.insertIfAbsent(orgId, provider.name(), va.id(), va.accountNumber(), va.ifsc(),
                    va.beneficiaryName());
            return accounts.findById(orgId).orElseThrow();
        });
        return view(stored);
    }

    private static BankTransferDetailsResponse view(VirtualAccount v) {
        return new BankTransferDetailsResponse(v.getAccountNumber(), v.getIfsc(), v.getBeneficiaryName());
    }
}
