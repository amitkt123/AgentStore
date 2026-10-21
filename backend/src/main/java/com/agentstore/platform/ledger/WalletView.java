package com.agentstore.platform.ledger;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

/** Builds an org's wallet view, for the buyer wallet endpoint and the admin adjustment endpoint. */
@Component
public class WalletView {

    private final WalletRepository walletRepository;
    private final BillingModeService billingModeService;
    private final LedgerPostingRepository postingRepository;

    public WalletView(WalletRepository walletRepository, BillingModeService billingModeService,
                      LedgerPostingRepository postingRepository) {
        this.walletRepository = walletRepository;
        this.billingModeService = billingModeService;
        this.postingRepository = postingRepository;
    }

    /**
     * Read-only: creates no wallet row and no ledger account, and takes no lock. A prepaid org that has never been
     * funded has no wallet row yet and reads as an empty wallet.
     */
    public WalletResponse of(UUID orgId) {
        BillingMode mode = billingModeService.modeFor(orgId);
        Wallet wallet = walletRepository.findReadOnlyByOrgId(orgId).orElse(null);
        BigDecimal reserved = wallet == null ? BigDecimal.ZERO : wallet.getReserved();
        BigDecimal cap = wallet == null ? null : wallet.getCap();
        Instant monthStart = YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        BigDecimal spent = postingRepository.runChargesSince(orgId, monthStart);

        if (mode == BillingMode.POSTPAID) {
            BigDecimal creditLimit = billingModeService.creditLimit(orgId);
            BigDecimal owed = billingModeService.owed(orgId);
            return new WalletResponse(mode, BigDecimal.ZERO, reserved, creditLimit.subtract(owed).subtract(reserved),
                    spent, null, creditLimit, owed, cap, reserved);
        }
        BigDecimal balance = wallet == null ? BigDecimal.ZERO : wallet.getBalance();
        return new WalletResponse(mode, balance, reserved, balance.subtract(reserved), spent, null, null,
                BigDecimal.ZERO, cap, reserved);
    }
}
