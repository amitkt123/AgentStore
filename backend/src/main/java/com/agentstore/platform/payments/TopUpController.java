package com.agentstore.platform.payments;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/wallet")
public class TopUpController {

    private final TopUpService topUps;
    private final PaymentAccess access;

    public TopUpController(TopUpService topUps, PaymentAccess access) {
        this.topUps = topUps;
        this.access = access;
    }

    @GetMapping("/payment-options")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','ADMIN')")
    public PaymentOptionsResponse options(@PathVariable UUID orgId) {
        access.requireOwnOrgOrAdmin(orgId);
        return topUps.options(orgId);
    }

    @PostMapping("/topups")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('BUYER_ADMIN')")
    public CheckoutResponse create(@PathVariable UUID orgId, @RequestBody TopUpRequest request) {
        var claims = access.requireOwnOrgAdmin(orgId);
        return topUps.createTopUp(orgId, claims.userId(), request.amount(), request.method());
    }

    @GetMapping("/topups")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','ADMIN')")
    public List<PaymentOrderView> list(@PathVariable UUID orgId) {
        access.requireOwnOrgOrAdmin(orgId);
        return topUps.recentTopUps(orgId);
    }
}
