package com.agentstore.platform.ledger;

import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

@RestController
public class WalletController {

    private final WalletRepository walletRepository;
    private final BillingModeService billingModeService;
    private final LedgerPostingRepository postingRepository;

    public WalletController(WalletRepository walletRepository, BillingModeService billingModeService,
                            LedgerPostingRepository postingRepository) {
        this.walletRepository = walletRepository;
        this.billingModeService = billingModeService;
        this.postingRepository = postingRepository;
    }

    /** Read-only: creates no wallet row and no ledger account, and takes no lock. */
    @GetMapping("/api/v1/orgs/{orgId}/wallet")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','ADMIN')")
    public WalletResponse wallet(@PathVariable UUID orgId) {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!orgId.equals(claims.orgId()) && claims.role() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot read another org's wallet");
        }
        return responseFor(orgId);
    }

    /**
     * The org's wallet view, shared with the admin adjustment endpoint. A prepaid org that has never been funded
     * has no wallet row yet and reads as an empty wallet.
     */
    public WalletResponse responseFor(UUID orgId) {
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
