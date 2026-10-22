package com.agentstore.platform.payouts;

import com.agentstore.platform.events.DomainEventPublisher;
import com.agentstore.platform.ledger.LedgerAccountKind;
import com.agentstore.platform.ledger.LedgerAccounts;
import com.agentstore.platform.ledger.LedgerService;
import com.agentstore.platform.ledger.LedgerTransactionType;
import com.agentstore.platform.ledger.Posting;
import com.agentstore.platform.payments.ReconciliationIssues;
import com.agentstore.platform.payments.WebhookProcessor;
import com.agentstore.platform.payments.WebhookProcessor.Outcome;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Settles withdrawals and payout-account validations from the payout provider. A webhook delivery is handled in one
 * transaction: verify the signature on the raw bytes, store the event once per ({@code payouts:{name}}, event_id)
 * in {@code payment_webhook_event}, act on it, mark it processed. A replayed delivery is a no-op; the same outcome
 * under a new event id is absorbed by the ledger keys {@code withdrawal-paid:{id}}, {@code payout-fee:{id}} and
 * {@code withdrawal-reversed:{id}}. {@link #settle} applies a polled payout with the same rules.
 * <p>
 * Lock order. A reversal goes through {@link WithdrawalService#reverse}, which locks the org's DEVELOPER_PAYABLE
 * balance row, then the withdrawal row, then (inside {@code post}) the balance rows in uuid order. So this class never
 * locks the withdrawal row before calling {@code reverse}: it finds the withdrawal with a plain read and lets
 * {@code reverse} take the locks. The processed path locks only the withdrawal row and then posts to
 * PAYOUT_CLEARING, RAZORPAYX_BALANCE and PAYOUT_FEES; it never touches DEVELOPER_PAYABLE, so it cannot form a cycle
 * with {@code reverse} or with a withdrawal request (payable, then clearing).
 */
@Service
public class PayoutWebhookProcessor {

    private static final Logger log = LoggerFactory.getLogger(PayoutWebhookProcessor.class);

    /** Prefix of {@code payment_webhook_event.provider} for payout events, keeping them apart from payment ones. */
    static final String EVENT_PROVIDER_PREFIX = "payouts:";

    private final PayoutProvider provider;
    private final WithdrawalRepository withdrawals;
    private final WithdrawalService withdrawalService;
    private final PayoutAccountRepository accounts;
    private final PayoutAccountService accountService;
    private final LedgerAccounts ledgerAccounts;
    private final LedgerService ledger;
    private final DomainEventPublisher events;
    private final ReconciliationIssues issues;
    private final JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    public PayoutWebhookProcessor(PayoutProvider provider, WithdrawalRepository withdrawals,
                                  WithdrawalService withdrawalService, PayoutAccountRepository accounts,
                                  PayoutAccountService accountService, LedgerAccounts ledgerAccounts,
                                  LedgerService ledger, DomainEventPublisher events, ReconciliationIssues issues,
                                  JdbcTemplate jdbc) {
        this.provider = provider;
        this.withdrawals = withdrawals;
        this.withdrawalService = withdrawalService;
        this.accounts = accounts;
        this.accountService = accountService;
        this.ledgerAccounts = ledgerAccounts;
        this.ledger = ledger;
        this.events = events;
        this.issues = issues;
        this.jdbc = jdbc;
    }

    /** The configured provider's name; the controller answers 404 for any other. */
    public String providerName() {
        return provider.name();
    }

    /**
     * @throws WebhookProcessor.BadSignatureException when the signature fails (nothing stored)
     * @throws PayoutProviderRejectedException when a correctly signed body cannot be parsed (nothing stored)
     */
    @Transactional
    public Outcome process(String providerName, byte[] body, Map<String, String> headers) {
        if (!provider.name().equals(providerName)) {
            throw new IllegalArgumentException("Not the configured payout provider: " + providerName);
        }
        if (!provider.verifyWebhook(body, headers)) {
            throw new WebhookProcessor.BadSignatureException("Payout webhook signature did not verify");
        }
        PayoutWebhookEvent event = provider.parseWebhook(body, headers);
        if (event.eventId() == null || event.eventId().isBlank() || event.kind() == null) {
            throw new PayoutProviderRejectedException("Payout webhook has no event id or kind");
        }

        String stored = EVENT_PROVIDER_PREFIX + providerName;
        // A concurrent delivery of the same event blocks here on the unique index until the first one commits.
        int inserted = jdbc.update("insert into payment_webhook_event (id, provider, event_id, type, payload) "
                        + "values (gen_random_uuid(), ?, ?, ?, ?::jsonb) on conflict (provider, event_id) do nothing",
                stored, event.eventId(), event.kind().name(), new String(body, StandardCharsets.UTF_8));
        if (inserted == 0) {
            return Outcome.DUPLICATE;
        }

        Outcome outcome = switch (event.kind()) {
            case PAYOUT_PROCESSED, PAYOUT_REVERSED, PAYOUT_FAILED -> payoutEvent(event);
            case VALIDATION_COMPLETED -> validation(event, "COMPLETED");
            case VALIDATION_FAILED -> validation(event, "FAILED");
            case OTHER -> Outcome.IGNORED;   // stored only
        };

        jdbc.update("update payment_webhook_event set processed_at = now() where provider = ? and event_id = ?",
                stored, event.eventId());
        return outcome;
    }

    /**
     * Applies a payout fetched from the provider (Task 5's poll) with the webhook's rules: PROCESSED settles,
     * REVERSED and FAILED give the money back, QUEUED and PROCESSING change nothing. Runs in its own transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome settle(UUID withdrawalId, PayoutProvider.ProviderPayout payout) {
        Withdrawal w = withdrawals.findById(withdrawalId).orElse(null);
        if (w == null) {
            log.warn("Polled payout {} for unknown withdrawal {}; ignored", payout.payoutId(), withdrawalId);
            return Outcome.IGNORED;
        }
        PayoutWebhookEvent.Kind kind = switch (payout.status() == null ? "" : payout.status()) {
            case "PROCESSED" -> PayoutWebhookEvent.Kind.PAYOUT_PROCESSED;
            case "REVERSED" -> PayoutWebhookEvent.Kind.PAYOUT_REVERSED;
            case "FAILED" -> PayoutWebhookEvent.Kind.PAYOUT_FAILED;
            default -> null;
        };
        if (kind == null) return Outcome.IGNORED;   // QUEUED / PROCESSING: still on its way
        return apply(w, kind, payout.payoutId(), payout.amount(), payout.fees(), payout.utr(), payout.failureReason());
    }

    private Outcome payoutEvent(PayoutWebhookEvent event) {
        Optional<Withdrawal> found = event.payoutId() == null ? Optional.empty()
                : withdrawals.findByProviderPayoutId(event.payoutId());
        if (found.isEmpty()) {
            log.warn("Payout event {} ({}) for unknown payout {}; stored only", event.eventId(), event.kind(),
                    event.payoutId());
            return Outcome.IGNORED;
        }
        return apply(found.get(), event.kind(), event.payoutId(), event.amount(), event.fees(), event.utr(),
                event.failureReason());
    }

    /** The shared rules. {@code w} is a plain (unlocked) read. */
    private Outcome apply(Withdrawal w, PayoutWebhookEvent.Kind kind, String payoutId, BigDecimal amount,
                          BigDecimal fees, String utr, String failureReason) {
        // Only the payout we recorded for this withdrawal can settle it (a REQUESTED one has none yet).
        if (payoutId == null || !payoutId.equals(w.getProviderPayoutId())) {
            log.warn("Payout {} ({}) is not the recorded payout {} of withdrawal {}; ignored", payoutId, kind,
                    w.getProviderPayoutId(), w.getId());
            return Outcome.IGNORED;
        }
        return switch (kind) {
            case PAYOUT_PROCESSED -> paid(w, payoutId, amount, fees, utr);
            case PAYOUT_REVERSED -> reversed(w, WithdrawalStatus.REVERSED, failureReason, "Payout reversed by the bank");
            case PAYOUT_FAILED -> reversed(w, WithdrawalStatus.FAILED, failureReason, "Payout failed at the provider");
            default -> throw new IllegalArgumentException("Not a payout outcome: " + kind);
        };
    }

    private Outcome reversed(Withdrawal w, WithdrawalStatus to, String reason, String defaultReason) {
        if (w.getStatus() == WithdrawalStatus.REVERSED || w.getStatus() == WithdrawalStatus.FAILED) {
            return Outcome.PROCESSED;   // already given back; reverse would do nothing either
        }
        // No lock here: reverse takes DEVELOPER_PAYABLE, then the withdrawal row, and re-checks the status.
        withdrawalService.reverse(w.getId(), to, reason == null || reason.isBlank() ? defaultReason : reason);
        return Outcome.PROCESSED;
    }

    private Outcome paid(Withdrawal w, String payoutId, BigDecimal amount, BigDecimal fees, String utr) {
        UUID clearing = ledgerAccounts.platformAccount(LedgerAccountKind.PAYOUT_CLEARING);
        UUID razorpayx = ledgerAccounts.platformAccount(LedgerAccountKind.RAZORPAYX_BALANCE);
        UUID payoutFees = ledgerAccounts.platformAccount(LedgerAccountKind.PAYOUT_FEES);

        entityManager.refresh(w, LockModeType.PESSIMISTIC_WRITE);
        UUID id = w.getId();
        switch (w.getStatus()) {
            case REVERSED, FAILED -> {
                // The provider paid money we already gave back to the developer: a double payment.
                log.error("Payout {} PROCESSED but withdrawal {} is already {}; not settled, double payment risk",
                        payoutId, id, w.getStatus());
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("withdrawalId", id.toString());
                detail.put("status", w.getStatus().name());
                detail.put("amount", amount == null ? null : amount.toPlainString());
                detail.put("utr", utr);
                issues.record("PAYOUT_AFTER_REVERSAL", payoutId, detail);
                return Outcome.IGNORED;
            }
            case PROCESSED -> {
                log.info("Withdrawal {} already PROCESSED; payout {} replay ignored", id, payoutId);
                return Outcome.PROCESSED;
            }
            default -> { /* REQUESTED (with this payout id) or PROCESSING: settle below */ }
        }
        if (amount == null || amount.compareTo(w.getAmount()) != 0) {
            log.error("Payout {} of {} does not match withdrawal {} amount {}; not settled", payoutId,
                    amount == null ? "null" : amount.toPlainString(), id, w.getAmount().toPlainString());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("withdrawalId", id.toString());
            detail.put("withdrawalAmount", w.getAmount().toPlainString());
            detail.put("payoutAmount", amount == null ? null : amount.toPlainString());
            issues.record("PAYOUT_AMOUNT_MISMATCH", payoutId, detail);
            return Outcome.IGNORED;
        }
        BigDecimal fee = fees == null ? BigDecimal.ZERO.setScale(2) : fees.setScale(2, RoundingMode.HALF_UP);
        if (fee.signum() < 0) {
            log.error("Payout {} reports a negative fee {}; fee not recorded", payoutId, fee.toPlainString());
            fee = BigDecimal.ZERO.setScale(2);
        }

        w.setStatus(WithdrawalStatus.PROCESSED);
        w.setUtr(utr);
        w.setFees(fee);
        w.setProcessedAt(Instant.now());
        withdrawals.save(w);

        BigDecimal a = w.getAmount();
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("withdrawalId", id.toString());
        reference.put("payoutId", payoutId);
        reference.put("utr", utr);
        LedgerService.PostResult posted = ledger.postWithResult(LedgerTransactionType.WITHDRAWAL,
                "withdrawal-paid:" + id, null, w.getOrgId(), reference,
                List.of(new Posting(clearing, a.negate()), new Posting(razorpayx, a)));
        if (!posted.created()) log.warn("withdrawal-paid:{} already posted; status set without moving money", id);
        if (fee.signum() > 0) {
            Map<String, Object> feeRef = new LinkedHashMap<>();
            feeRef.put("withdrawalId", id.toString());
            feeRef.put("payoutId", payoutId);
            feeRef.put("reason", "payout fee");
            ledger.post(LedgerTransactionType.ADJUSTMENT, "payout-fee:" + id, null, w.getOrgId(), feeRef,
                    List.of(new Posting(razorpayx, fee), new Posting(payoutFees, fee.negate())));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("withdrawalId", id.toString());
        payload.put("amount", a.toPlainString());
        payload.put("utr", utr);
        events.publish("payout.withdrawal_processed", w.getOrgId(), id, payload);
        log.info("Withdrawal {} PROCESSED by payout {} (fee {})", id, payoutId, fee.toPlainString());
        return Outcome.PROCESSED;
    }

    private Outcome validation(PayoutWebhookEvent event, String status) {
        Optional<PayoutAccount> account = event.validationId() == null ? Optional.empty()
                : accounts.findByProviderValidationId(event.validationId());
        if (account.isEmpty()) {
            log.warn("Validation event {} for unknown validation {}; stored only", event.eventId(), event.validationId());
            return Outcome.IGNORED;
        }
        accountService.applyValidation(account.get().getId(), new PayoutProvider.ProviderValidation(
                event.validationId(), status, "COMPLETED".equals(status) ? event.registeredName() : null,
                event.failureReason()));
        return Outcome.PROCESSED;
    }
}
