package com.agentstore.platform.payouts;

import com.agentstore.platform.events.DomainEventPublisher;
import com.agentstore.platform.ledger.LedgerAccountBalance;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.ledger.LedgerAccountBalanceRepository;
import com.agentstore.platform.ledger.LedgerAccountKind;
import com.agentstore.platform.ledger.LedgerAccounts;
import com.agentstore.platform.ledger.LedgerService;
import com.agentstore.platform.ledger.LedgerTransactionRepository;
import com.agentstore.platform.ledger.LedgerTransactionType;
import com.agentstore.platform.ledger.Posting;
import com.agentstore.platform.payments.ReconciliationIssues;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Developer withdrawals: earnings leave DEVELOPER_PAYABLE for PAYOUT_CLEARING in one transaction, and only after it
 * commits is the payout sent to the provider (never inside a DB transaction).
 * <p>
 * Lock order everywhere: the org's DEVELOPER_PAYABLE balance row, then the withdrawal row, then (inside
 * {@code post}) the balance rows in uuid order. Holding the payable lock serialises an org's withdrawals, so the
 * balance, daily-limit and request-id checks made under it cannot be raced.
 * <p>
 * Submitting is safe to repeat: only a REQUESTED withdrawal is sent, its id is the provider idempotency key (the
 * provider returns the first payout for a repeated key), and the move to PROCESSING is made under the withdrawal row
 * lock only while it is still REQUESTED. Two overlapping submitters can both call the provider, but they get the same
 * payout and only one records it.
 */
@Service
public class WithdrawalService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalService.class);

    /** A submit run stops taking new withdrawals after this long, well inside the job's ShedLock lockAtMostFor. */
    static final Duration JOB_BUDGET = Duration.ofMinutes(4);
    private static final int SUBMIT_BATCH = 100;
    private static final int MAX_LIST = 100;

    /** What {@link #requestOrExisting} did: {@code created} is false when the request id was already used. */
    public record Requested(WithdrawalView withdrawal, boolean created) {}

    private record Created(UUID withdrawalId, boolean created) {}

    private record Submission(UUID orgId, UUID withdrawalId, String fundAccountId, BigDecimal amount, PayoutMode mode) {}

    private final WithdrawalRepository withdrawals;
    private final PayoutAccountRepository payoutAccounts;
    private final PayoutAccountService accountService;
    private final PayoutProvider provider;
    private final PayoutProperties props;
    private final LedgerAccounts ledgerAccounts;
    private final LedgerAccountBalanceRepository balances;
    private final LedgerTransactionRepository ledgerTransactions;
    private final LedgerService ledger;
    private final DomainEventPublisher events;
    private final ReconciliationIssues issues;
    private final TransactionTemplate tx;
    private final OrgRepository orgs;

    @PersistenceContext
    private EntityManager entityManager;

    public WithdrawalService(WithdrawalRepository withdrawals, PayoutAccountRepository payoutAccounts,
                             PayoutAccountService accountService, PayoutProvider provider, PayoutProperties props,
                             LedgerAccounts ledgerAccounts, LedgerAccountBalanceRepository balances,
                             LedgerTransactionRepository ledgerTransactions, LedgerService ledger,
                             DomainEventPublisher events, ReconciliationIssues issues, TransactionTemplate tx,
                             OrgRepository orgs) {
        this.orgs = orgs;
        this.withdrawals = withdrawals;
        this.payoutAccounts = payoutAccounts;
        this.accountService = accountService;
        this.provider = provider;
        this.props = props;
        this.ledgerAccounts = ledgerAccounts;
        this.balances = balances;
        this.ledgerTransactions = ledgerTransactions;
        this.ledger = ledger;
        this.events = events;
        this.issues = issues;
        this.tx = tx;
    }

    // ---- requesting ----

    public WithdrawalView request(UUID orgId, UUID userId, UUID requestId, BigDecimal amount) {
        return requestOrExisting(orgId, userId, requestId, amount).withdrawal();
    }

    /**
     * Creates a withdrawal and submits it, or returns the one already created under {@code requestId}. Must not run
     * inside a transaction: the provider is called after the money-moving transaction commits.
     *
     * @throws PayoutApiException INVALID_FIELD, AMOUNT_INVALID, DEVELOPER_NOT_VERIFIED, MONEY_FROZEN,
     *                            NO_VERIFIED_PAYOUT_ACCOUNT, BELOW_MINIMUM, INSUFFICIENT_EARNINGS or DAILY_LIMIT_REACHED
     */
    public Requested requestOrExisting(UUID orgId, UUID userId, UUID requestId, BigDecimal amount) {
        if (requestId == null) {
            throw new PayoutApiException(HttpStatus.BAD_REQUEST, "INVALID_FIELD", "requestId is required");
        }
        Optional<Withdrawal> existing = withdrawals.findByOrgIdAndRequestId(orgId, requestId);
        if (existing.isPresent()) {
            return new Requested(view(existing.get().getId()), false);
        }
        BigDecimal a = validAmount(amount);
        accountService.requireWithdrawable(orgId);   // fail fast; checked again under the lock

        // Resolve the ledger accounts before taking any lock.
        UUID payable = ledgerAccounts.orgAccount(orgId, LedgerAccountKind.DEVELOPER_PAYABLE);
        UUID clearing = ledgerAccounts.platformAccount(LedgerAccountKind.PAYOUT_CLEARING);

        Created c = tx.execute(s -> create(orgId, userId, requestId, a, payable, clearing));
        if (c.created()) {
            // The money is in PAYOUT_CLEARING; now ask the provider, outside any transaction.
            submitQuietly(c.withdrawalId());
        }
        return new Requested(view(c.withdrawalId()), c.created());
    }

    private Created create(UUID orgId, UUID userId, UUID requestId, BigDecimal amount, UUID payable, UUID clearing) {
        LedgerAccountBalance balance = balances.findForUpdate(payable)
                .orElseThrow(() -> new IllegalStateException("No balance row for payable " + payable));
        entityManager.refresh(balance);

        // Under the payable lock a concurrent request with the same id has either committed (seen here) or waits.
        Optional<Withdrawal> existing = withdrawals.findByOrgIdAndRequestId(orgId, requestId);
        if (existing.isPresent()) {
            return new Created(existing.get().getId(), false);
        }

        PayoutAccount account = accountService.requireWithdrawable(orgId);
        if (amount.compareTo(props.withdrawalMin()) < 0) {
            throw new PayoutApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BELOW_MINIMUM",
                    "The minimum withdrawal is " + props.withdrawalMin().toPlainString());
        }
        if (amount.compareTo(balance.getBalance()) > 0) {
            throw new PayoutApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_EARNINGS",
                    "You can withdraw at most " + balance.getBalance().max(BigDecimal.ZERO).toPlainString());
        }
        BigDecimal today = withdrawals.sumCountedSince(orgId, PayoutAccountService.startOfTodayUtc());
        if (today.add(amount).compareTo(props.withdrawalDailyLimit()) > 0) {
            throw new PayoutApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DAILY_LIMIT_REACHED",
                    "This would exceed today's withdrawal limit of " + props.withdrawalDailyLimit().toPlainString());
        }

        Withdrawal w = new Withdrawal();
        w.setOrgId(orgId);
        w.setPayoutAccountId(account.getId());
        w.setRequestId(requestId);
        w.setAmount(amount);
        w.setMode(PayoutMode.forType(account.getType()));
        w.setStatus(WithdrawalStatus.REQUESTED);
        w.setRequestedByUserId(userId);
        w = withdrawals.saveAndFlush(w);

        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("withdrawalId", w.getId().toString());
        reference.put("payoutAccountId", account.getId().toString());
        LedgerService.PostResult posted = ledger.postWithResult(LedgerTransactionType.WITHDRAWAL,
                "withdrawal:" + w.getId(), null, orgId, reference,
                List.of(new Posting(payable, amount.negate()), new Posting(clearing, amount)));
        if (!posted.created()) {
            throw new IllegalStateException("Withdrawal " + w.getId() + " was already posted");
        }
        log.info("Withdrawal {} of {} requested for org {}", w.getId(), amount.toPlainString(), orgId);
        return new Created(w.getId(), true);
    }

    // ---- submitting ----

    /** Run by {@link WithdrawalSubmitJob}: resubmits REQUESTED withdrawals older than {@code retry-after}. */
    public void submitDue() {
        Instant now = Instant.now();
        Instant stopAt = now.plus(JOB_BUDGET);
        for (UUID id : withdrawals.requestedIdsBefore(now.minus(props.retryAfter()), PageRequest.of(0, SUBMIT_BATCH))) {
            if (Instant.now().isAfter(stopAt)) {
                log.warn("Withdrawal submit run out of time; remaining withdrawals wait for the next run");
                return;
            }
            submitQuietly(id);
        }
    }

    private void submitQuietly(UUID withdrawalId) {
        try {
            submit(withdrawalId);
        } catch (RuntimeException e) {
            // It stays REQUESTED; the submit job tries it again.
            log.error("Submitting withdrawal {} failed; left for retry", withdrawalId, e);
        }
    }

    /**
     * Sends one REQUESTED withdrawal to the provider, paying the fund account it was created with. Anything else is
     * left alone, so this is safe to repeat. Must not run inside a transaction.
     */
    public void submit(UUID withdrawalId) {
        Submission s = tx.execute(st -> {
            Withdrawal w = withdrawals.findById(withdrawalId).orElse(null);
            if (w == null || w.getStatus() != WithdrawalStatus.REQUESTED) return null;
            PayoutAccount account = payoutAccounts.findById(w.getPayoutAccountId()).orElseThrow();
            return new Submission(w.getOrgId(), withdrawalId, account.getProviderFundAccountId(), w.getAmount(), w.getMode());
        });
        if (s == null) {
            log.info("Withdrawal {} is not REQUESTED; not submitted", withdrawalId);
            return;
        }
        // A freeze is an investigation: stay REQUESTED (no reversal) so an unfreeze resumes it.
        if (orgs.isMoneyFrozen(s.orgId()).orElse(false)) {
            log.info("Withdrawal {} not submitted: money is frozen for org {}", withdrawalId, s.orgId());
            return;
        }
        PayoutProvider.ProviderPayout payout;
        try {
            payout = provider.createPayout(s.fundAccountId(), s.amount(), s.mode(), withdrawalId.toString());
        } catch (PayoutProviderUnavailableException e) {
            log.warn("Payout provider unavailable for withdrawal {}; left REQUESTED: {}", withdrawalId, e.getMessage());
            return;
        } catch (PayoutInsufficientBalanceException e) {
            log.error("Payout provider balance too low for withdrawal {} of {}; left REQUESTED", withdrawalId,
                    s.amount().toPlainString());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("withdrawalId", withdrawalId.toString());
            detail.put("amount", s.amount().toPlainString());
            issues.record("PAYOUT_FUNDS_LOW", "razorpayx-balance", detail);
            return;
        } catch (PayoutProviderRejectedException e) {
            log.warn("Payout provider rejected withdrawal {}: {}", withdrawalId, e.getMessage());
            tx.executeWithoutResult(st -> reverseLocked(withdrawalId, WithdrawalStatus.FAILED,
                    "Provider rejected the payout: " + e.getMessage(), true));
            return;
        }
        tx.executeWithoutResult(st -> recordSubmitted(withdrawalId, payout));
    }

    private void recordSubmitted(UUID withdrawalId, PayoutProvider.ProviderPayout payout) {
        Withdrawal w = withdrawals.findByIdForUpdate(withdrawalId).orElseThrow();
        if (w.getStatus() != WithdrawalStatus.REQUESTED) {
            if (w.getStatus() == WithdrawalStatus.REVERSED || w.getStatus() == WithdrawalStatus.FAILED) {
                log.error("Withdrawal {} is {} but the provider made payout {}", withdrawalId, w.getStatus(),
                        payout.payoutId());
            } else {
                log.info("Withdrawal {} already {}; payout {} left as recorded", withdrawalId, w.getStatus(),
                        payout.payoutId());
            }
            return;
        }
        w.setStatus(WithdrawalStatus.PROCESSING);
        w.setProviderPayoutId(payout.payoutId());
        w.setSubmittedAt(Instant.now());
        withdrawals.save(w);
    }

    // ---- reversing ----

    /**
     * Returns the withdrawal's money to DEVELOPER_PAYABLE, once, and marks it {@code to} (REVERSED or FAILED). The
     * money comes from PAYOUT_CLEARING, or from RAZORPAYX_BALANCE when {@code withdrawal-paid:{id}} was posted (the
     * payout left our provider balance and the bank sent it back). A withdrawal already REVERSED or FAILED is left
     * as is. Joins the caller's transaction when there is one.
     */
    @Transactional
    public void reverse(UUID withdrawalId, WithdrawalStatus to, String reason) {
        reverseLocked(withdrawalId, to, reason, false);
    }

    private void reverseLocked(UUID withdrawalId, WithdrawalStatus to, String reason, boolean onlyIfRequested) {
        if (to != WithdrawalStatus.REVERSED && to != WithdrawalStatus.FAILED) {
            throw new IllegalArgumentException("A withdrawal can only be reversed to REVERSED or FAILED, not " + to);
        }
        Withdrawal w = withdrawals.findById(withdrawalId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown withdrawal " + withdrawalId));
        UUID payable = ledgerAccounts.orgAccount(w.getOrgId(), LedgerAccountKind.DEVELOPER_PAYABLE);
        UUID clearing = ledgerAccounts.platformAccount(LedgerAccountKind.PAYOUT_CLEARING);
        UUID razorpayx = ledgerAccounts.platformAccount(LedgerAccountKind.RAZORPAYX_BALANCE);

        balances.findForUpdate(payable).ifPresent(entityManager::refresh);
        entityManager.refresh(w, LockModeType.PESSIMISTIC_WRITE);
        if (w.getStatus() == WithdrawalStatus.REVERSED || w.getStatus() == WithdrawalStatus.FAILED) return;
        if (onlyIfRequested && w.getStatus() != WithdrawalStatus.REQUESTED) {
            log.info("Withdrawal {} moved on to {}; not failed", withdrawalId, w.getStatus());
            return;
        }

        boolean paid = ledgerTransactions.findByIdempotencyKey("withdrawal-paid:" + withdrawalId).isPresent();
        BigDecimal amount = w.getAmount();
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("withdrawalId", withdrawalId.toString());
        reference.put("status", to.name());
        reference.put("reason", reason);
        LedgerService.PostResult posted = ledger.postWithResult(LedgerTransactionType.WITHDRAWAL_REVERSED,
                "withdrawal-reversed:" + withdrawalId, null, w.getOrgId(), reference,
                List.of(new Posting(payable, amount), new Posting(paid ? razorpayx : clearing, amount.negate())));
        if (!posted.created()) {
            log.warn("Withdrawal {} reversal replayed; status set without moving money", withdrawalId);
        }

        w.setStatus(to);
        w.setFailureReason(reason == null || reason.isBlank() ? "Withdrawal " + to.name().toLowerCase() : reason);
        withdrawals.save(w);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("withdrawalId", withdrawalId.toString());
        payload.put("amount", amount.toPlainString());
        payload.put("status", to.name());
        payload.put("reason", w.getFailureReason());
        events.publish("payout.withdrawal_reversed", w.getOrgId(), withdrawalId, payload);
        log.info("Withdrawal {} {}: {}", withdrawalId, to, w.getFailureReason());
    }

    // ---- reading ----

    /** The org's withdrawals, newest first; {@code limit} is clamped to 1..100. */
    public List<WithdrawalView> recent(UUID orgId, int limit) {
        int n = Math.max(1, Math.min(MAX_LIST, limit));
        return tx.execute(s -> {
            List<Withdrawal> rows = withdrawals.findByOrgIdOrderByCreatedAtDescIdDesc(orgId, PageRequest.of(0, n));
            Map<UUID, PayoutAccount> byId = payoutAccounts.findAllById(
                            rows.stream().map(Withdrawal::getPayoutAccountId).distinct().toList())
                    .stream().collect(Collectors.toMap(PayoutAccount::getId, Function.identity()));
            return rows.stream().map(w -> WithdrawalView.of(w, byId.get(w.getPayoutAccountId()))).toList();
        });
    }

    private WithdrawalView view(UUID withdrawalId) {
        return tx.execute(s -> {
            Withdrawal w = withdrawals.findById(withdrawalId).orElseThrow();
            return WithdrawalView.of(w, payoutAccounts.findById(w.getPayoutAccountId()).orElse(null));
        });
    }

    static BigDecimal validAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new PayoutApiException(HttpStatus.BAD_REQUEST, "AMOUNT_INVALID",
                    "Amount must be positive with at most 2 decimal places");
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }
}
