package com.agentstore.platform.payouts;

import com.agentstore.platform.payments.ReconciliationIssues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Polls what webhooks should have told us: payout account validations still PENDING and withdrawals still PROCESSING
 * after {@code stuck-after}. Not transactional: provider calls and {@link PayoutWebhookProcessor#settle} (which opens
 * its own transaction) must run outside any transaction.
 */
@Service
public class PayoutMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(PayoutMaintenanceService.class);
    static final Duration VALIDATION_POLL_AFTER = Duration.ofMinutes(1);
    private static final int BATCH = 100;

    private final PayoutProvider provider;
    private final PayoutAccountRepository accounts;
    private final PayoutAccountService accountService;
    private final WithdrawalRepository withdrawals;
    private final PayoutWebhookProcessor processor;
    private final ReconciliationIssues issues;
    private final PayoutProperties props;

    public PayoutMaintenanceService(PayoutProvider provider, PayoutAccountRepository accounts,
                                    PayoutAccountService accountService, WithdrawalRepository withdrawals,
                                    PayoutWebhookProcessor processor, ReconciliationIssues issues,
                                    PayoutProperties props) {
        this.provider = provider;
        this.accounts = accounts;
        this.accountService = accountService;
        this.withdrawals = withdrawals;
        this.processor = processor;
        this.issues = issues;
        this.props = props;
    }

    public void run(Instant now) {
        pollValidations(now);
        pollStuckWithdrawals(now);
    }

    private void pollValidations(Instant now) {
        for (UUID id : accounts.pendingValidationIdsBefore(now.minus(VALIDATION_POLL_AFTER), PageRequest.of(0, BATCH))) {
            try {
                PayoutAccount a = accounts.findById(id).orElse(null);
                if (a == null || a.getProviderValidationId() == null) continue;
                Optional<PayoutProvider.ProviderValidation> v = provider.fetchValidation(a.getProviderValidationId());
                if (v.isPresent()) accountService.applyValidation(id, v.get());
            } catch (RuntimeException e) {
                log.warn("Polling the validation of payout account {} failed; retried next run", id, e);
            }
        }
    }

    private void pollStuckWithdrawals(Instant now) {
        for (UUID id : withdrawals.processingIdsSubmittedBefore(now.minus(props.stuckAfter()), PageRequest.of(0, BATCH))) {
            try {
                Withdrawal w = withdrawals.findById(id).orElse(null);
                if (w == null || w.getProviderPayoutId() == null) continue;
                String payoutId = w.getProviderPayoutId();
                Optional<PayoutProvider.ProviderPayout> payout = provider.fetchPayout(payoutId);
                payout.ifPresent(p -> processor.settle(id, p));
                Withdrawal after = withdrawals.findById(id).orElseThrow();
                if (after.getStatus() == WithdrawalStatus.PROCESSING) {
                    log.error("Withdrawal {} has been PROCESSING since {} (payout {}, provider status {})", id,
                            after.getSubmittedAt(), payoutId, payout.map(PayoutProvider.ProviderPayout::status).orElse("unknown"));
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("withdrawalId", id.toString());
                    detail.put("orgId", after.getOrgId().toString());
                    detail.put("amount", after.getAmount().toPlainString());
                    detail.put("submittedAt", String.valueOf(after.getSubmittedAt()));
                    detail.put("providerStatus", payout.map(PayoutProvider.ProviderPayout::status).orElse("unknown"));
                    issues.record("WITHDRAWAL_STUCK", payoutId, detail);
                }
            } catch (RuntimeException e) {
                log.warn("Polling stuck withdrawal {} failed; retried next run", id, e);
            }
        }
    }
}
