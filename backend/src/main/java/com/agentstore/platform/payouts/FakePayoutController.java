package com.agentstore.platform.payouts;

import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.payments.WebhookProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dev-only stand-in for the bank settling a payout: builds a signed fake payout webhook for a withdrawal and runs it
 * through the real {@link PayoutWebhookProcessor}. Exists only while the configured payout provider is {@code fake}.
 */
@RestController
@ConditionalOnProperty(prefix = "agentstore.money.payouts", name = "provider", havingValue = "fake")
public class FakePayoutController implements PayoutApiController {

    public record SimulateRequest(UUID withdrawalId, String outcome) {}

    private final PayoutProvider provider;
    private final PayoutWebhookProcessor processor;
    private final WithdrawalRepository withdrawals;
    private final PayoutAccountRepository accounts;

    public FakePayoutController(PayoutProvider provider, PayoutWebhookProcessor processor,
                                WithdrawalRepository withdrawals, PayoutAccountRepository accounts) {
        this.provider = provider;
        this.processor = processor;
        this.withdrawals = withdrawals;
        this.accounts = accounts;
    }

    @PostMapping("/api/v1/dev/payouts/simulate")
    @PreAuthorize("hasAnyRole('DEVELOPER','ADMIN')")
    public Map<String, Object> simulate(@RequestBody SimulateRequest request) {
        if (!(provider instanceof FakePayoutProvider fake)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (request == null || request.withdrawalId() == null || request.outcome() == null) {
            throw new PayoutApiException(HttpStatus.BAD_REQUEST, "INVALID_FIELD", "withdrawalId and outcome are required");
        }
        PayoutWebhookEvent.Kind kind = switch (request.outcome()) {
            case "PROCESSED" -> PayoutWebhookEvent.Kind.PAYOUT_PROCESSED;
            case "REVERSED" -> PayoutWebhookEvent.Kind.PAYOUT_REVERSED;
            case "FAILED" -> PayoutWebhookEvent.Kind.PAYOUT_FAILED;
            default -> throw new PayoutApiException(HttpStatus.BAD_REQUEST, "INVALID_FIELD",
                    "outcome must be PROCESSED, REVERSED or FAILED");
        };
        Withdrawal w = withdrawals.findById(request.withdrawalId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Withdrawal not found"));
        JwtService.Claims claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        if (claims.role() != UserRole.ADMIN && !w.getOrgId().equals(claims.orgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot act on another org's withdrawal");
        }
        if ((w.getStatus() != WithdrawalStatus.PROCESSING && w.getStatus() != WithdrawalStatus.PROCESSED)
                || w.getProviderPayoutId() == null) {
            throw new PayoutApiException(HttpStatus.CONFLICT, "WITHDRAWAL_NOT_SIMULATABLE",
                    "Only a PROCESSING or PROCESSED withdrawal can be simulated; this one is " + w.getStatus());
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        String utr = null;
        if (kind == PayoutWebhookEvent.Kind.PAYOUT_PROCESSED) {
            utr = "FAKEUTR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            fields.put("amount", w.getAmount().toPlainString());
            fields.put("fees", "0.00");
            fields.put("utr", utr);
        } else {
            fields.put("failureReason", "Simulated " + request.outcome().toLowerCase());
        }
        // Keep the fake's own record in step, so polling and reconciliation see the same outcome.
        fake.markPayout(w.getProviderPayoutId(), request.outcome(), utr,
                kind == PayoutWebhookEvent.Kind.PAYOUT_PROCESSED ? null : (String) fields.get("failureReason"));
        FakePayoutProvider.SignedEvent event = fake.signedEvent(kind, w.getProviderPayoutId(), fields);
        WebhookProcessor.Outcome outcome = processor.process(fake.name(), event.body(), event.headers());

        Withdrawal after = withdrawals.findById(w.getId()).orElseThrow();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", outcome.name());
        body.put("withdrawal", WithdrawalView.of(after, accounts.findById(after.getPayoutAccountId()).orElse(null)));
        return body;
    }
}
