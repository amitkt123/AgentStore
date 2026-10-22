package com.agentstore.platform.payments;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves a MISSING_LOCALLY reconciliation issue by crediting the provider's payment, never by a wallet adjustment. */
@RestController
@RequestMapping("/api/v1/admin/payments/payments")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPaymentRecoveryController {

    private final PaymentRecoveryService recovery;

    public AdminPaymentRecoveryController(PaymentRecoveryService recovery) {
        this.recovery = recovery;
    }

    @PostMapping("/{providerPaymentId}/apply")
    public Map<String, Object> apply(@PathVariable String providerPaymentId) {
        PaymentRecoveryService.Applied a = recovery.apply(providerPaymentId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentId", a.paymentId());
        body.put("paymentOrderId", a.paymentOrderId().toString());
        body.put("result", a.result().name());
        body.put("issuesResolved", a.issuesResolved());
        return body;
    }
}
