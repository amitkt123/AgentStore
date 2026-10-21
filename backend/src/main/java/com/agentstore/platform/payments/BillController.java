package com.agentstore.platform.payments;

import com.agentstore.platform.ledger.BillingMode;
import com.agentstore.platform.ledger.BillingModeService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/bills")
public class BillController {

    private final BillingCycleService billing;
    private final TopUpService topUps;
    private final BillingModeService billingModes;
    private final PaymentAccess access;

    public BillController(BillingCycleService billing, TopUpService topUps, BillingModeService billingModes,
                          PaymentAccess access) {
        this.billing = billing;
        this.topUps = topUps;
        this.billingModes = billingModes;
        this.access = access;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','ADMIN')")
    public List<BillView> list(@PathVariable UUID orgId) {
        access.requireOwnOrgOrAdmin(orgId);
        return billing.bills(orgId);
    }

    /** A checkout for exactly the bill amount. Bank transfer only tops up a wallet, so it cannot pay a bill. */
    @PostMapping("/{billId}/pay")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('BUYER_ADMIN')")
    public CheckoutResponse pay(@PathVariable UUID orgId, @PathVariable UUID billId,
                                @RequestBody PayBillRequest request) {
        var claims = access.requireOwnOrgAdmin(orgId);
        if (request.method() == PaymentMethod.BANK_TRANSFER) {
            throw new PaymentApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_METHOD_DISABLED",
                    "Bank transfer only tops up a wallet; pay the bill by UPI, card or netbanking");
        }
        topUps.checkMethod(request.method());
        if (billingModes.modeFor(orgId) != BillingMode.POSTPAID) {
            throw new PaymentApiException(HttpStatus.CONFLICT, "PREPAID_NO_BILLS",
                    "Prepaid orgs pay up front and have no bills");
        }
        BuyerBill bill = billing.payableBill(orgId, billId);
        return topUps.createOrder(orgId, claims.userId(), "BILL", bill.getId(), bill.getAmount(), request.method());
    }
}
