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

import java.util.UUID;

@RestController
public class WalletController {

    private final WalletView walletView;

    public WalletController(WalletView walletView) {
        this.walletView = walletView;
    }

    /** Read-only: creates no wallet row and no ledger account, and takes no lock. */
    @GetMapping("/api/v1/orgs/{orgId}/wallet")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','ADMIN')")
    public WalletResponse wallet(@PathVariable UUID orgId) {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!orgId.equals(claims.orgId()) && claims.role() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot read another org's wallet");
        }
        return walletView.of(orgId);
    }
}
