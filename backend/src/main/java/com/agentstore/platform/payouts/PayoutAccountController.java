package com.agentstore.platform.payouts;

import com.agentstore.platform.identity.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
public class PayoutAccountController implements PayoutApiController {

    private final PayoutAccountService accounts;

    public PayoutAccountController(PayoutAccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/api/v1/developer/payout-account")
    @PreAuthorize("hasRole('DEVELOPER')")
    @ResponseStatus(HttpStatus.CREATED)
    public PayoutAccountView add(@RequestBody PayoutAccountRequest request) {
        var claims = claims();
        return accounts.add(claims.orgId(), claims.userId(), request);
    }

    @GetMapping("/api/v1/developer/payouts")
    @PreAuthorize("hasRole('DEVELOPER')")
    public PayoutsSummaryResponse summary() {
        return accounts.summary(claims().orgId());
    }

    private static JwtService.Claims claims() {
        return (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
