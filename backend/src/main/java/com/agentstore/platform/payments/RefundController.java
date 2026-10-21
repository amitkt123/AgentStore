package com.agentstore.platform.payments;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/wallet")
public class RefundController {

    private final RefundService refunds;
    private final PaymentAccess access;

    public RefundController(RefundService refunds, PaymentAccess access) {
        this.refunds = refunds;
        this.access = access;
    }

    /** The refundable total and its split; for {@code amount} if given, else for the whole refundable total. */
    @GetMapping("/refundable")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','ADMIN')")
    public RefundableResponse refundable(@PathVariable UUID orgId, @RequestParam(required = false) BigDecimal amount) {
        access.requireOwnOrgOrAdmin(orgId);
        BigDecimal refundable = refunds.refundable(orgId);
        if (amount != null) {
            refunds.checkRequestable(orgId, amount);
        }
        return new RefundableResponse(refundable, refunds.plan(orgId, amount == null ? refundable : amount));
    }

    /** 201 with a new refund, or 200 with the one already created under the same {@code requestId}. */
    @PostMapping("/refunds")
    @PreAuthorize("hasRole('BUYER_ADMIN')")
    public ResponseEntity<RefundView> request(@PathVariable UUID orgId, @RequestBody RefundRequest request) {
        var claims = access.requireOwnOrgAdmin(orgId);
        RefundService.Requested r = refunds.requestOrExisting(orgId, claims.userId(), request.requestId(),
                request.amount());
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(r.refund());
    }

    @GetMapping("/refunds")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','ADMIN')")
    public List<RefundView> list(@PathVariable UUID orgId) {
        access.requireOwnOrgOrAdmin(orgId);
        return refunds.recent(orgId);
    }
}
